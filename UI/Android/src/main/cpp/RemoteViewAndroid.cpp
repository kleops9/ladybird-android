/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "RemoteViewAndroid.h"
#include "HubConnection.h"
#include "JNIHelpers.h"
#include <AK/HashMap.h>
#include <LibCore/Resource.h>
#include <LibGfx/Bitmap.h>
#include <LibGfx/SharedImageBuffer.h>
#include <LibGfx/SystemTheme.h>
#include <LibURL/Parser.h>
#include <LibWebView/URL.h>
#include <android/native_window.h>
#include <stdarg.h>

namespace Ladybird {

static HashMap<u64, RemoteViewAndroid*> s_views;

static struct {
    jmethodID invalidate_layout;
    jmethodID on_load_start;
    jmethodID on_load_finish;
    jmethodID on_url_change;
    jmethodID on_title_change;
    jmethodID on_favicon_change;
    jmethodID on_navigation_buttons_state_changed;
    jmethodID on_theme_color_change;
    jmethodID on_focused_editable_change;
    jmethodID on_new_web_view;
    jmethodID on_close;
    jmethodID on_activate_tab;
    jmethodID on_context_menu;
    jmethodID on_request_alert;
    jmethodID on_request_confirm;
    jmethodID on_request_prompt;
    jmethodID on_request_select_dropdown;
    jmethodID on_web_content_crashed;
    jmethodID on_find_in_page;
} s_methods;

void RemoteViewAndroid::cache_java_methods(JNIEnv* env, jclass clazz)
{
    auto get = [&](char const* name, char const* signature) {
        auto method = env->GetMethodID(clazz, name, signature);
        if (!method) {
            warnln("WebViewImplementation: missing Java method {} {}", name, signature);
            VERIFY_NOT_REACHED();
        }
        return method;
    };

    s_methods.invalidate_layout = get("invalidateLayout", "()V");
    s_methods.on_load_start = get("onLoadStart", "(Ljava/lang/String;Z)V");
    s_methods.on_load_finish = get("onLoadFinish", "(Ljava/lang/String;)V");
    s_methods.on_url_change = get("onUrlChange", "(Ljava/lang/String;)V");
    s_methods.on_title_change = get("onTitleChange", "(Ljava/lang/String;)V");
    s_methods.on_favicon_change = get("onFaviconChange", "([III)V");
    s_methods.on_navigation_buttons_state_changed = get("onNavigationButtonsStateChanged", "(ZZ)V");
    s_methods.on_theme_color_change = get("onThemeColorChange", "(I)V");
    s_methods.on_focused_editable_change = get("onFocusedEditableChange", "(Z)V");
    s_methods.on_new_web_view = get("onNewWebView", "(ZJ)Ljava/lang/String;");
    s_methods.on_close = get("onClose", "()V");
    s_methods.on_activate_tab = get("onActivateTab", "()V");
    s_methods.on_context_menu = get("onContextMenu", "(ILjava/lang/String;II)V");
    s_methods.on_request_alert = get("onRequestAlert", "(Ljava/lang/String;)V");
    s_methods.on_request_confirm = get("onRequestConfirm", "(Ljava/lang/String;)V");
    s_methods.on_request_prompt = get("onRequestPrompt", "(Ljava/lang/String;Ljava/lang/String;)V");
    s_methods.on_request_select_dropdown = get("onRequestSelectDropdown", "([Ljava/lang/String;[I[ZI)V");
    s_methods.on_web_content_crashed = get("onWebContentCrashed", "()V");
    s_methods.on_find_in_page = get("onFindInPageResult", "(II)V");
}

RemoteViewAndroid* RemoteViewAndroid::find(u64 view_id)
{
    return s_views.get(view_id).value_or(nullptr);
}

static Core::AnonymousBuffer load_system_theme()
{
    auto theme_ini = Core::Resource::load_from_uri("resource://themes/Default.ini"sv);
    if (theme_ini.is_error()) {
        warnln("Unable to load the system theme: {}", theme_ini.error());
        return {};
    }
    auto theme = Gfx::load_system_theme(theme_ini.value()->filesystem_path().to_byte_string());
    if (theme.is_error()) {
        warnln("Unable to load the system theme: {}", theme.error());
        return {};
    }
    return theme.release_value();
}

RemoteViewAndroid::RemoteViewAndroid(jobject java_instance, WebView::IsPrivate is_private, Optional<u64> existing_view_id, double device_pixel_ratio)
    : m_java_instance(java_instance)
    , m_is_private(is_private)
    , m_device_pixel_ratio(device_pixel_ratio)
{
    auto& hub = HubConnection::the();
    if (existing_view_id.has_value()) {
        m_view_id = *existing_view_id;
        m_state = hub.view_description(m_view_id);
    } else {
        auto view = hub.create_view({
            .is_private = is_private,
            .device_pixel_ratio = device_pixel_ratio,
            .maximum_frames_per_second = 60,
            .display_id = {},
            .screen_shows_high_dynamic_range = false,
            .system_theme = load_system_theme(),
            .screen_rects = {},
            .owner_view_id = {},
            .websites_prefer_dark = false,
            .is_tab = true,
        });
        m_view_id = view.view_id();
        m_state = view.take_state();
    }
    s_views.set(m_view_id, this);

    // The frames are copied out of shared memory into the tab's Surface, so the Compositor shares them there.
    hub.async_view_set_presents_from_cpu_memory(m_view_id, true);
    hub.async_view_set_current_tab_of_window(m_view_id, true);
}

RemoteViewAndroid::~RemoteViewAndroid()
{
    s_views.remove(m_view_id);
    if (m_window)
        ANativeWindow_release(m_window);
    if (auto* hub = HubConnection::the_if_connected())
        hub->async_destroy_view(m_view_id);
    JavaEnvironment env(global_vm);
    env.get()->DeleteGlobalRef(m_java_instance);
}

void RemoteViewAndroid::call_java(jmethodID method, ...) const
{
    JavaEnvironment env(global_vm);
    va_list arguments;
    va_start(arguments, method);
    env.get()->CallVoidMethodV(m_java_instance, method, arguments);
    va_end(arguments);
}

void RemoteViewAndroid::call_java_with_string(jmethodID method, StringView string) const
{
    JavaEnvironment env(global_vm);
    auto jstr = env.jstring_from_ak_string(String::from_utf8_with_replacement_character(string));
    env.get()->CallVoidMethod(m_java_instance, method, jstr);
    env.get()->DeleteLocalRef(jstr);
}

// Kotlin -> Hub

void RemoteViewAndroid::load(StringView url_string)
{
    auto url = URL::Parser::basic_parse(url_string);
    if (!url.has_value()) {
        warnln("Not a URL: {}", url_string);
        return;
    }
    HubConnection::the().async_view_load(m_view_id, *url, Web::Bindings::NavigationHistoryBehavior::Auto);
}

void RemoteViewAndroid::reload()
{
    HubConnection::the().async_view_reload(m_view_id, WebView::BypassCache::No);
}

void RemoteViewAndroid::stop_loading()
{
    HubConnection::the().async_view_stop_loading(m_view_id);
}

void RemoteViewAndroid::traverse_history(int delta)
{
    HubConnection::the().async_view_traverse_the_history_by_delta(m_view_id, delta);
}

void RemoteViewAndroid::zoom(int direction)
{
    auto& hub = HubConnection::the();
    if (direction > 0)
        hub.async_view_zoom_in(m_view_id);
    else if (direction < 0)
        hub.async_view_zoom_out(m_view_id);
    else
        hub.async_view_reset_zoom(m_view_id);
}

void RemoteViewAndroid::pinch(float focus_x, float focus_y, float scale_delta, bool continues_gesture)
{
    Web::PinchEvent event {
        .position = Gfx::IntPoint { static_cast<int>(focus_x), static_cast<int>(focus_y) }.to_type<Web::DevicePixels>(),
        .scale_delta = scale_delta,
        .continues_gesture = continues_gesture,
    };
    HubConnection::the().async_view_enqueue_pinch_event(m_view_id, event);
}

void RemoteViewAndroid::find_in_page(Utf16String const& query)
{
    HubConnection::the().async_view_find_in_page(m_view_id, query, CaseSensitivity::CaseInsensitive, true);
}

void RemoteViewAndroid::find_in_page_next()
{
    HubConnection::the().async_view_find_in_page_next_match(m_view_id);
}

void RemoteViewAndroid::find_in_page_previous()
{
    HubConnection::the().async_view_find_in_page_previous_match(m_view_id);
}

void RemoteViewAndroid::find_in_page_end()
{
    HubConnection::the().async_view_find_in_page_end(m_view_id);
}

void RemoteViewAndroid::alert_closed()
{
    HubConnection::the().async_view_alert_closed(m_view_id);
}

void RemoteViewAndroid::confirm_closed(bool accepted)
{
    HubConnection::the().async_view_confirm_closed(m_view_id, accepted);
}

void RemoteViewAndroid::prompt_closed(Optional<Utf16String> response)
{
    HubConnection::the().async_view_prompt_closed(m_view_id, move(response));
}

void RemoteViewAndroid::select_dropdown_closed(Optional<u32> item_id)
{
    HubConnection::the().async_view_select_dropdown_closed(m_view_id, item_id);
}

void RemoteViewAndroid::commit_text(Utf16String const& text)
{
    HubConnection::the().async_view_commit_text_from_input_method(m_view_id, text, 0, 0);
}

void RemoteViewAndroid::set_surface(ANativeWindow* window)
{
    if (m_window)
        ANativeWindow_release(m_window);
    m_window = window;
    m_window_buffer_size = {};
    present();
}

void RemoteViewAndroid::set_presentation_paused(bool paused)
{
    m_presentation_paused = paused;
    if (!paused)
        present();
}

void RemoteViewAndroid::set_viewport_size(int width, int height)
{
    if (width <= 0 || height <= 0)
        return;
    m_viewport_size = { width, height };
    HubConnection::the().async_view_set_viewport(m_view_id, Web::DevicePixelSize { width, height }, m_device_pixel_ratio);
}

void RemoteViewAndroid::set_device_pixel_ratio(double device_pixel_ratio)
{
    m_device_pixel_ratio = device_pixel_ratio;
    if (!m_viewport_size.is_empty())
        HubConnection::the().async_view_set_viewport(m_view_id, Web::DevicePixelSize { m_viewport_size.width(), m_viewport_size.height() }, m_device_pixel_ratio);
}

void RemoteViewAndroid::set_has_focus(bool has_focus)
{
    HubConnection::the().async_view_set_has_system_focus(m_view_id, has_focus);
}

void RemoteViewAndroid::set_visible(bool visible)
{
    auto& hub = HubConnection::the();
    hub.async_view_set_system_visibility_state(m_view_id, visible ? Web::HTML::VisibilityState::Visible : Web::HTML::VisibilityState::Hidden);
    hub.async_view_set_current_tab_of_window(m_view_id, visible);
    if (visible)
        hub.async_active_view_changed(m_view_id);
}

void RemoteViewAndroid::mouse_event(Web::MouseEvent::Type type, float x, float y, float screen_x, float screen_y, Web::UIEvents::MouseButton button, int wheel_delta_x, int wheel_delta_y, bool is_touch, int click_count)
{
    auto buttons = Web::UIEvents::MouseButton::None;
    if (type == Web::MouseEvent::Type::MouseDown || (type == Web::MouseEvent::Type::MouseMove && button != Web::UIEvents::MouseButton::None))
        buttons = button;

    Web::MouseEvent event {
        .type = type,
        .position = Gfx::IntPoint { static_cast<int>(x), static_cast<int>(y) }.to_type<Web::DevicePixels>(),
        .screen_position = Gfx::IntPoint { static_cast<int>(screen_x), static_cast<int>(screen_y) }.to_type<Web::DevicePixels>(),
        .button = button,
        .buttons = buttons,
    };
    if (type == Web::MouseEvent::Type::MouseWheel) {
        event.wheel_delta_x = wheel_delta_x;
        event.wheel_delta_y = wheel_delta_y;
        event.wheel_delta_precision = Web::WheelDeltaPrecision::Precise;
        event.wheel_tick_120ths_x = Web::wheel_tick_120ths_from_pixel_delta(wheel_delta_x, Web::WheelDeltaPrecision::Precise);
        event.wheel_tick_120ths_y = Web::wheel_tick_120ths_from_pixel_delta(wheel_delta_y, Web::WheelDeltaPrecision::Precise);
    }
    if (type == Web::MouseEvent::Type::MouseDown || type == Web::MouseEvent::Type::MouseUp)
        event.click_count = click_count;
    if (is_touch)
        event.pointer_data = Web::PointerInputData { Web::PointerProperties { .pointer_type = Web::PointerType::Touch } };

    HubConnection::the().async_view_enqueue_mouse_event(m_view_id, m_next_input_id++, event);
}

void RemoteViewAndroid::key_event(Web::KeyEvent::Type type, Web::UIEvents::KeyCode key, Web::UIEvents::KeyModifier modifiers, u32 code_point)
{
    Web::KeyEvent event {
        .type = type,
        .key = key,
        .modifiers = modifiers,
        .code_point = code_point,
        .should_insert_text = type == Web::KeyEvent::Type::KeyDown && code_point != 0,
    };
    HubConnection::the().async_view_enqueue_key_event(m_view_id, m_next_input_id++, event);
}

// Frames

void RemoteViewAndroid::release_backing_store(i32 bitmap_id)
{
    if (bitmap_id < 0 || !m_backing_stores_page_id.has_value())
        return;
    if (auto* hub = HubConnection::the_if_connected())
        hub->async_release_backing_store(*m_backing_stores_page_id, bitmap_id);
}

void RemoteViewAndroid::did_install_backing_stores(u64 page_id, Vector<i32> bitmap_ids, Vector<Gfx::SharedImage> backing_stores)
{
    auto front_bitmap_id = bitmap_ids.first();
    if (auto result = m_backing_stores.install(move(bitmap_ids), move(backing_stores)); result.is_error()) {
        warnln("Could not install backing stores for page {}: {}", page_id, result.error());
        if (auto* hub = HubConnection::the_if_connected())
            hub->async_release_backing_store(page_id, front_bitmap_id);
        return;
    }
    m_backing_stores_page_id = page_id;
}

void RemoteViewAndroid::did_add_backing_stores(Vector<i32> bitmap_ids, Vector<Gfx::SharedImage> backing_stores)
{
    if (auto result = m_backing_stores.add(move(bitmap_ids), move(backing_stores)); result.is_error())
        warnln("Could not add backing stores: {}", result.error());
}

void RemoteViewAndroid::did_retire_backing_stores(Vector<i32> bitmap_ids)
{
    m_backing_stores.retire(bitmap_ids);
}

void RemoteViewAndroid::did_present_backing_store(u64 page_id, i32 bitmap_id, Gfx::IntSize size, double device_pixel_ratio)
{
    // Nothing here reads the backing store this frame replaced, so the Compositor may paint into it again right away. The
    // frame itself goes into the Surface once the Hub accepts it.
    auto result = m_backing_stores.present(bitmap_id, size, device_pixel_ratio);
    if (result.bitmap_id_to_release >= 0) {
        if (auto* hub = HubConnection::the_if_connected())
            hub->async_release_backing_store(page_id, result.bitmap_id_to_release);
    }
}

void RemoteViewAndroid::did_accept_presented_backing_store()
{
    present();
    call_java(s_methods.invalidate_layout);
}

bool RemoteViewAndroid::copy_frame_to_rgba(u8* destination, size_t destination_pitch, int width, int height) const
{
    auto frame = m_backing_stores.displayed_frame();
    RefPtr<Gfx::Bitmap> bitmap;
    Gfx::IntSize frame_size;
    if (frame.has_value() && frame->shared_image_buffer) {
        bitmap = frame->shared_image_buffer->bitmap_if_present();
        frame_size = frame->size;
    }

    int rows = 0;
    int columns = 0;
    bool swap_red_and_blue = true;
    if (bitmap) {
        rows = min(height, min(frame_size.height(), bitmap->height()));
        columns = min(width, min(frame_size.width(), bitmap->width()));
        swap_red_and_blue = bitmap->format() == Gfx::BitmapFormat::BGRA8888 || bitmap->format() == Gfx::BitmapFormat::BGRx8888;
    }

    for (int y = 0; y < height; ++y) {
        auto* out = reinterpret_cast<u32*>(destination + y * destination_pitch);
        int x = 0;
        if (y < rows) {
            auto const* in = reinterpret_cast<u32 const*>(bitmap->scanline_u8(y));
            if (swap_red_and_blue) {
                // BGRA in memory is 0xAARRGGBB as a little-endian u32; RGBA is 0xAABBGGRR.
                for (; x < columns; ++x) {
                    u32 pixel = in[x];
                    out[x] = 0xFF000000u | (pixel & 0x0000FF00u) | ((pixel >> 16) & 0xFFu) | ((pixel & 0xFFu) << 16);
                }
            } else {
                for (; x < columns; ++x)
                    out[x] = in[x] | 0xFF000000u;
            }
        }
        for (; x < width; ++x)
            out[x] = 0xFFFFFFFFu;
    }
    return bitmap != nullptr;
}

// Writes the frame on display straight into the tab's window buffer.
void RemoteViewAndroid::present()
{
    if (!m_window || m_presentation_paused || m_viewport_size.is_empty())
        return;
    auto width = m_viewport_size.width();
    auto height = m_viewport_size.height();

    if (m_window_buffer_size != m_viewport_size) {
        ANativeWindow_setBuffersGeometry(m_window, width, height, WINDOW_FORMAT_RGBA_8888);
        m_window_buffer_size = m_viewport_size;
    }

    ANativeWindow_Buffer buffer {};
    if (ANativeWindow_lock(m_window, &buffer, nullptr) != 0)
        return;
    copy_frame_to_rgba(static_cast<u8*>(buffer.bits), buffer.stride * 4, min(width, buffer.width), min(height, buffer.height));
    ANativeWindow_unlockAndPost(m_window);
}

// Hub -> Kotlin

void RemoteViewAndroid::did_change_state(Hub::ViewState state)
{
    auto previous = exchange(m_state, move(state));
    if (previous.can_navigate_back != m_state.can_navigate_back || previous.can_navigate_forward != m_state.can_navigate_forward)
        call_java(s_methods.on_navigation_buttons_state_changed, m_state.can_navigate_back, m_state.can_navigate_forward);
    if (previous.input_method_state.is_enabled != m_state.input_method_state.is_enabled)
        did_change_input_method_state();
}

void RemoteViewAndroid::did_start_loading()
{
    JavaEnvironment env(global_vm);
    auto url = env.jstring_from_ak_string(m_state.url.to_string());
    env.get()->CallVoidMethod(m_java_instance, s_methods.on_load_start, url, false);
    env.get()->DeleteLocalRef(url);
}

void RemoteViewAndroid::did_finish_loading(URL::URL const& url)
{
    call_java_with_string(s_methods.on_load_finish, url.to_string());
}

void RemoteViewAndroid::did_change_title(Utf16String const& title)
{
    call_java_with_string(s_methods.on_title_change, title.to_utf8());
}

void RemoteViewAndroid::did_change_url(URL::URL const& url)
{
    call_java_with_string(s_methods.on_url_change, url.to_string());
}

void RemoteViewAndroid::did_change_favicon(Optional<Gfx::ShareableBitmap> const& favicon)
{
    if (!favicon.has_value() || !favicon->is_valid())
        return;
    auto const* bitmap = favicon->bitmap();
    auto width = bitmap->width();
    auto height = bitmap->height();
    if (width <= 0 || height <= 0)
        return;

    JavaEnvironment env(global_vm);
    auto pixels = env.get()->NewIntArray(width * height);
    auto* data = env.get()->GetIntArrayElements(pixels, nullptr);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x)
            data[y * width + x] = static_cast<jint>(bitmap->get_pixel(x, y).value());
    }
    env.get()->ReleaseIntArrayElements(pixels, data, 0);
    env.get()->CallVoidMethod(m_java_instance, s_methods.on_favicon_change, pixels, width, height);
    env.get()->DeleteLocalRef(pixels);
}

void RemoteViewAndroid::did_change_theme_color(Gfx::Color color)
{
    call_java(s_methods.on_theme_color_change, static_cast<jint>(color.value()));
}

void RemoteViewAndroid::did_change_input_method_state()
{
    auto enabled = m_state.input_method_state.is_enabled;
    if (enabled == m_input_method_enabled)
        return;
    m_input_method_enabled = enabled;
    call_java(s_methods.on_focused_editable_change, enabled);
}

String RemoteViewAndroid::did_request_new_view(u64 new_view_id, Web::HTML::ActivateTab activate_tab)
{
    JavaEnvironment env(global_vm);
    auto handle = static_cast<jstring>(env.get()->CallObjectMethod(m_java_instance, s_methods.on_new_web_view, activate_tab == Web::HTML::ActivateTab::Yes, static_cast<jlong>(new_view_id)));
    if (!handle)
        return {};
    char const* raw = env.get()->GetStringUTFChars(handle, nullptr);
    auto result = String::from_utf8_with_replacement_character(StringView { raw, strlen(raw) });
    env.get()->ReleaseStringUTFChars(handle, raw);
    env.get()->DeleteLocalRef(handle);
    return result;
}

void RemoteViewAndroid::did_request_activation()
{
    call_java(s_methods.on_activate_tab);
}

void RemoteViewAndroid::did_request_close()
{
    call_java(s_methods.on_close);
}

// Context menu kinds, mirrored in WebViewImplementation.kt.
void RemoteViewAndroid::did_request_context_menu(WebView::ContextMenuRequest const& request)
{
    int kind = 0;
    Optional<URL::URL> url;
    if (request.kind == WebView::ContextMenuKind::Image && request.navigation.has_value()) {
        kind = 2;
        url = request.navigation->url;
    } else if (request.link_navigation.has_value()) {
        kind = 1;
        url = request.link_navigation->url;
    }

    JavaEnvironment env(global_vm);
    jstring url_string = url.has_value() ? env.jstring_from_ak_string(url->to_string()) : nullptr;
    env.get()->CallVoidMethod(m_java_instance, s_methods.on_context_menu, kind, url_string, request.widget_position.x(), request.widget_position.y());
    if (url_string)
        env.get()->DeleteLocalRef(url_string);
}

void RemoteViewAndroid::did_request_alert(Utf16String const& message)
{
    call_java_with_string(s_methods.on_request_alert, message.to_utf8());
}

void RemoteViewAndroid::did_request_confirm(Utf16String const& message)
{
    call_java_with_string(s_methods.on_request_confirm, message.to_utf8());
}

void RemoteViewAndroid::did_request_prompt(Utf16String const& message, Utf16String const& default_value)
{
    JavaEnvironment env(global_vm);
    auto jmessage = env.jstring_from_ak_string(message.to_utf8());
    auto jdefault = env.jstring_from_ak_string(default_value.to_utf8());
    env.get()->CallVoidMethod(m_java_instance, s_methods.on_request_prompt, jmessage, jdefault);
    env.get()->DeleteLocalRef(jmessage);
    env.get()->DeleteLocalRef(jdefault);
}

void RemoteViewAndroid::did_request_select_dropdown(Vector<Web::HTML::SelectItem> const& items)
{
    // Option groups are flattened into one list, where group labels become disabled entries.
    Vector<String> labels;
    Vector<jint> ids;
    Vector<jboolean> enabled;
    jint selected_index = -1;

    auto add_option = [&](Web::HTML::SelectItemOption const& option, bool indent) {
        if (option.selected)
            selected_index = static_cast<jint>(labels.size());
        auto label = option.label.to_utf8();
        labels.append(indent ? MUST(String::formatted("    {}", label)) : label);
        ids.append(static_cast<jint>(option.id));
        enabled.append(!option.disabled);
    };

    for (auto const& item : items) {
        item.visit(
            [&](Web::HTML::SelectItemOption const& option) { add_option(option, false); },
            [&](Web::HTML::SelectItemOptionGroup const& group) {
                labels.append(group.label.to_utf8());
                ids.append(-1);
                enabled.append(false);
                for (auto const& option : group.items)
                    add_option(option, true);
            },
            [&](Web::HTML::SelectItemSeparator const&) {});
    }

    JavaEnvironment env(global_vm);
    auto* jenv = env.get();
    auto string_class = jenv->FindClass("java/lang/String");
    auto jlabels = jenv->NewObjectArray(labels.size(), string_class, nullptr);
    for (size_t i = 0; i < labels.size(); ++i) {
        auto jlabel = env.jstring_from_ak_string(labels[i]);
        jenv->SetObjectArrayElement(jlabels, i, jlabel);
        jenv->DeleteLocalRef(jlabel);
    }
    auto jids = jenv->NewIntArray(ids.size());
    jenv->SetIntArrayRegion(jids, 0, ids.size(), ids.data());
    auto jenabled = jenv->NewBooleanArray(enabled.size());
    jenv->SetBooleanArrayRegion(jenabled, 0, enabled.size(), enabled.data());

    jenv->CallVoidMethod(m_java_instance, s_methods.on_request_select_dropdown, jlabels, jids, jenabled, selected_index);

    jenv->DeleteLocalRef(jlabels);
    jenv->DeleteLocalRef(jids);
    jenv->DeleteLocalRef(jenabled);
    jenv->DeleteLocalRef(string_class);
}

void RemoteViewAndroid::did_find_in_page(size_t current_match_index, Optional<size_t> total_match_count)
{
    jint total = total_match_count.has_value() ? static_cast<jint>(*total_match_count) : -1;
    call_java(s_methods.on_find_in_page, static_cast<jint>(current_match_index), total);
}

void RemoteViewAndroid::did_crash()
{
    call_java(s_methods.on_web_content_crashed);
}

}
