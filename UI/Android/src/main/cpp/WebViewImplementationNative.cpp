/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "WebViewImplementationNative.h"
#include "JNIHelpers.h"
#include <LibCore/EventLoop.h>
#include <LibGfx/Bitmap.h>
#include <LibGfx/ImmutableBitmap.h>
#include <LibGfx/Painter.h>
#include <LibWeb/Crypto/Crypto.h>
#include <LibWebView/Application.h>
#include <LibWebView/CookieJar.h>
#include <LibWebView/ViewImplementation.h>
#include <LibWebView/WebContentClient.h>
#include <android/bitmap.h>
#include <android/native_window.h>
#include <jni.h>

namespace Ladybird {

static struct {
    jmethodID bind_webcontent;
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
    jmethodID on_resource_status_change;
    jmethodID on_web_content_crashed;
    jmethodID on_request_download;
    jmethodID on_find_in_page;
} s_methods;

void WebViewImplementationNative::cache_java_methods(JNIEnv* env, jclass clazz)
{
    auto get = [&](char const* name, char const* signature) {
        auto method = env->GetMethodID(clazz, name, signature);
        if (!method) {
            warnln("WebViewImplementation: missing Java method {} {}", name, signature);
            VERIFY_NOT_REACHED();
        }
        return method;
    };

    s_methods.bind_webcontent = get("bindWebContentService", "(I)V");
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
    s_methods.on_resource_status_change = get("onResourceStatusChange", "(I)V");
    s_methods.on_web_content_crashed = get("onWebContentCrashed", "()V");
    s_methods.on_request_download = get("onRequestDownload", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    s_methods.on_find_in_page = get("onFindInPageResult", "(II)V");
}

WebViewImplementationNative::WebViewImplementationNative(jobject thiz)
    : m_java_instance(thiz)
{
    // NOTE: m_java_instance's global ref is controlled by the JNI bindings
    install_callbacks();
    initialize_client(CreateNewClient::Yes);
}

WebViewImplementationNative::WebViewImplementationNative(jobject thiz, WebViewImplementationNative& parent, u64 page_index)
    : m_java_instance(thiz)
{
    install_callbacks();
    m_client_state.client = parent.m_client_state.client;
    m_client_state.page_index = page_index;
    initialize_client(CreateNewClient::No);
}

void WebViewImplementationNative::install_callbacks()
{
    on_ready_to_paint = [this]() {
        // Put the new frame on screen right away, then let the Java side know (for the pinch zoom preview).
        present();
        JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(m_java_instance, s_methods.invalidate_layout);
    };

    auto call_with_string = [this](jmethodID method, String const& string) {
        JavaEnvironment env(global_vm);
        auto jstr = env.jstring_from_ak_string(string);
        env.get()->CallVoidMethod(m_java_instance, method, jstr);
        env.get()->DeleteLocalRef(jstr);
    };

    on_load_start = [this](URL::URL const& url, bool is_redirect) {
        JavaEnvironment env(global_vm);
        auto url_string = env.jstring_from_ak_string(url.to_string());
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_load_start, url_string, is_redirect);
        env.get()->DeleteLocalRef(url_string);
    };

    on_load_finish = [call_with_string](URL::URL const& url) {
        call_with_string(s_methods.on_load_finish, url.to_string());
    };

    on_url_change = [call_with_string](URL::URL const& url) {
        call_with_string(s_methods.on_url_change, url.to_string());
    };

    on_title_change = [call_with_string](ByteString const& title) {
        call_with_string(s_methods.on_title_change, String::from_utf8_with_replacement_character(title));
    };

    on_favicon_change = [this](Gfx::Bitmap const& bitmap) {
        JavaEnvironment env(global_vm);
        auto width = bitmap.width();
        auto height = bitmap.height();
        if (width <= 0 || height <= 0)
            return;
        auto pixels = env.get()->NewIntArray(width * height);
        auto* data = env.get()->GetIntArrayElements(pixels, nullptr);
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x)
                data[y * width + x] = static_cast<jint>(bitmap.get_pixel(x, y).value());
        }
        env.get()->ReleaseIntArrayElements(pixels, data, 0);
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_favicon_change, pixels, width, height);
        env.get()->DeleteLocalRef(pixels);
    };

    on_navigation_buttons_state_changed = [this](bool back_enabled, bool forward_enabled) {
        JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_navigation_buttons_state_changed, back_enabled, forward_enabled);
    };

    on_theme_color_change = [this](Gfx::Color color) {
        JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_theme_color_change, static_cast<jint>(color.value()));
    };

    on_focused_editable_change = [this](bool is_editable) {
        JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_focused_editable_change, is_editable);
    };

    on_new_web_view = [this](Web::HTML::ActivateTab activate_tab, Web::HTML::WebViewHints, Optional<u64> page_index) -> String {
        JavaEnvironment env(global_vm);
        jlong index = page_index.has_value() ? static_cast<jlong>(*page_index) : -1;
        auto handle = static_cast<jstring>(env.get()->CallObjectMethod(m_java_instance, s_methods.on_new_web_view, activate_tab == Web::HTML::ActivateTab::Yes, index));
        if (!handle)
            return {};
        char const* raw = env.get()->GetStringUTFChars(handle, nullptr);
        auto result = MUST(String::from_utf8(StringView { raw, strlen(raw) }));
        env.get()->ReleaseStringUTFChars(handle, raw);
        env.get()->DeleteLocalRef(handle);
        return result;
    };

    on_close = [this]() {
        JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_close);
    };

    on_activate_tab = [this]() {
        JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_activate_tab);
    };

    // Context menu kinds, mirrored in WebViewImplementation.kt.
    auto request_context_menu = [this](int kind, Optional<URL::URL const&> url, Gfx::IntPoint position) {
        JavaEnvironment env(global_vm);
        jstring url_string = url.has_value() ? env.jstring_from_ak_string(url->to_string()) : nullptr;
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_context_menu, kind, url_string, position.x(), position.y());
        if (url_string)
            env.get()->DeleteLocalRef(url_string);
    };

    on_context_menu_request = [request_context_menu](Gfx::IntPoint position) {
        request_context_menu(0, {}, position);
    };

    on_link_context_menu_request = [request_context_menu](URL::URL const& url, Gfx::IntPoint position) {
        request_context_menu(1, url, position);
    };

    on_image_context_menu_request = [request_context_menu](URL::URL const& url, Gfx::IntPoint position, Optional<Gfx::ShareableBitmap> const&) {
        request_context_menu(2, url, position);
    };

    on_request_alert = [call_with_string](String const& message) {
        call_with_string(s_methods.on_request_alert, message);
    };

    on_request_confirm = [call_with_string](String const& message) {
        call_with_string(s_methods.on_request_confirm, message);
    };

    on_request_prompt = [this](String const& message, String const& default_) {
        JavaEnvironment env(global_vm);
        auto jmessage = env.jstring_from_ak_string(message);
        auto jdefault = env.jstring_from_ak_string(default_);
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_request_prompt, jmessage, jdefault);
        env.get()->DeleteLocalRef(jmessage);
        env.get()->DeleteLocalRef(jdefault);
    };

    on_request_select_dropdown = [this](Gfx::IntPoint, i32, Vector<Web::HTML::SelectItem> items) {
        // Flatten option groups into a single list; group labels become disabled entries.
        Vector<String> labels;
        Vector<jint> ids;
        Vector<jboolean> enabled;
        jint selected_index = -1;

        auto add_option = [&](Web::HTML::SelectItemOption const& option, bool indent) {
            if (option.selected)
                selected_index = static_cast<jint>(labels.size());
            labels.append(indent ? MUST(String::formatted("    {}", option.label)) : option.label);
            ids.append(static_cast<jint>(option.id));
            enabled.append(!option.disabled);
        };

        for (auto const& item : items) {
            item.visit(
                [&](Web::HTML::SelectItemOption const& option) { add_option(option, false); },
                [&](Web::HTML::SelectItemOptionGroup const& group) {
                    labels.append(group.label);
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
    };

    on_resource_status_change = [this](i32 count_waiting) {
        JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_resource_status_change, count_waiting);
    };

    on_request_download = [this](URL::URL const& url, ByteString const& content_disposition, ByteString const& mime_type) {
        JavaEnvironment env(global_vm);
        auto jurl = env.jstring_from_ak_string(url.to_string());
        auto jdisposition = env.jstring_from_ak_string(String::from_utf8_with_replacement_character(content_disposition));
        auto jmime = env.jstring_from_ak_string(String::from_utf8_with_replacement_character(mime_type));
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_request_download, jurl, jdisposition, jmime);
        env.get()->DeleteLocalRef(jurl);
        env.get()->DeleteLocalRef(jdisposition);
        env.get()->DeleteLocalRef(jmime);
    };

    on_find_in_page = [this](size_t current_match_index, Optional<size_t> const& total_match_count) {
        JavaEnvironment env(global_vm);
        jint total = total_match_count.has_value() ? static_cast<jint>(*total_match_count) : -1;
        env.get()->CallVoidMethod(m_java_instance, s_methods.on_find_in_page, static_cast<jint>(current_match_index), total);
    };
}

void WebViewImplementationNative::set_private_browsing(bool private_browsing)
{
    client().set_private_browsing(private_browsing);
}

String WebViewImplementationNative::cookies_for_url(URL::URL const& url)
{
    auto& jar = client().is_private_browsing() ? WebView::Application::private_cookie_jar() : WebView::Application::cookie_jar();
    return jar.get_cookie(url, Web::Cookie::Source::Http);
}

void WebViewImplementationNative::initialize_client(WebView::ViewImplementation::CreateNewClient create_new_client)
{
    if (create_new_client == CreateNewClient::Yes) {
        m_client_state = {};
        m_client_state.client = bind_web_content_client();
    } else {
        m_client_state.client->register_view(m_client_state.page_index, *this);
    }

    m_client_state.client->on_web_content_process_crash = [this] {
        // The Java side shows a "sad tab" page and recreates the view on reload.
        Core::deferred_invoke([this] {
            JavaEnvironment env(global_vm);
            env.get()->CallVoidMethod(m_java_instance, s_methods.on_web_content_crashed);
        });
    };

    m_client_state.client_handle = MUST(Web::Crypto::generate_random_uuid());
    client().async_set_window_handle(m_client_state.page_index, m_client_state.client_handle);

    client().async_set_device_pixels_per_css_pixel(m_client_state.page_index, m_device_pixel_ratio * m_zoom_level);

    set_system_visibility_state(Web::HTML::VisibilityState::Visible);

    languages_changed();
    autoplay_settings_changed();
    do_not_track_changed();
}

void WebViewImplementationNative::update_zoom()
{
    client().async_set_device_pixels_per_css_pixel(m_client_state.page_index, m_device_pixel_ratio * m_zoom_level);
    handle_resize();
}

// The frame WebContent most recently finished, and how much of it was painted.
// NOTE: Reading the front bitmap on the main thread is safe: WebContent paints into the back bitmap, and the two are
//       only swapped by a did_paint message, which is also handled on the main thread.
Gfx::Bitmap const* WebViewImplementationNative::current_frame(Gfx::IntSize& painted_size) const
{
    if (m_client_state.has_usable_bitmap && m_client_state.front_bitmap.bitmap) {
        painted_size = m_client_state.front_bitmap.last_painted_size.to_type<int>();
        return m_client_state.front_bitmap.bitmap.ptr();
    }
    if (m_backup_bitmap) {
        painted_size = m_backup_bitmap_size.to_type<int>();
        return m_backup_bitmap.ptr();
    }
    return nullptr;
}

// Copies the current frame into RGBA memory (an Android bitmap or window buffer), swapping Ladybird's BGRA to RGBA.
// Anything outside the painted area is filled with white.
void WebViewImplementationNative::copy_frame_to_rgba(u8* destination, size_t destination_pitch, int width, int height) const
{
    Gfx::IntSize painted_size;
    auto const* bitmap = current_frame(painted_size);
    int rows = 0;
    int columns = 0;
    if (bitmap) {
        rows = min(height, min(painted_size.height(), bitmap->height()));
        columns = min(width, min(painted_size.width(), bitmap->width()));
    }
    for (int y = 0; y < height; ++y) {
        auto* out = reinterpret_cast<u32*>(destination + y * destination_pitch);
        int x = 0;
        if (y < rows) {
            auto const* in = reinterpret_cast<u32 const*>(bitmap->scanline_u8(y));
            // BGRA in memory is 0xAARRGGBB as a little-endian u32; RGBA is 0xAABBGGRR. Simple enough to vectorize.
            for (; x < columns; ++x) {
                u32 pixel = in[x];
                out[x] = 0xFF000000u | (pixel & 0x0000FF00u) | ((pixel >> 16) & 0xFFu) | ((pixel & 0xFFu) << 16);
            }
        }
        for (; x < width; ++x)
            out[x] = 0xFFFFFFFFu;
    }
}

void WebViewImplementationNative::paint_into_bitmap(void* android_bitmap_raw, AndroidBitmapInfo const& info)
{
    // Software bitmaps only for now!
    VERIFY((info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) == 0);
    VERIFY(info.format == ANDROID_BITMAP_FORMAT_RGBA_8888);
    copy_frame_to_rgba(static_cast<u8*>(android_bitmap_raw), info.stride, info.width, info.height);
}

void WebViewImplementationNative::set_surface(ANativeWindow* window)
{
    if (m_window)
        ANativeWindow_release(m_window);
    m_window = window;
    m_window_buffer_size = {};
    present();
}

void WebViewImplementationNative::set_presentation_paused(bool paused)
{
    m_presentation_paused = paused;
    if (!paused)
        present();
}

// Writes the current frame straight into the view's window buffer: no Java drawing, no bitmap upload.
void WebViewImplementationNative::present()
{
    if (!m_window || m_presentation_paused)
        return;
    int width = m_viewport_size.width().value();
    int height = m_viewport_size.height().value();
    if (width <= 0 || height <= 0)
        return;

    if (m_window_buffer_size != Gfx::IntSize { width, height }) {
        ANativeWindow_setBuffersGeometry(m_window, width, height, WINDOW_FORMAT_RGBA_8888);
        m_window_buffer_size = { width, height };
    }

    ANativeWindow_Buffer buffer {};
    if (ANativeWindow_lock(m_window, &buffer, nullptr) != 0)
        return;
    copy_frame_to_rgba(static_cast<u8*>(buffer.bits), buffer.stride * 4, min(width, buffer.width), min(height, buffer.height));
    ANativeWindow_unlockAndPost(m_window);
}

void WebViewImplementationNative::set_viewport_geometry(int w, int h)
{
    m_viewport_size = { w, h };
    handle_resize();
}

void WebViewImplementationNative::set_device_pixel_ratio(float f)
{
    m_device_pixel_ratio = f;
    client().async_set_device_pixels_per_css_pixel(m_client_state.page_index, m_device_pixel_ratio * m_zoom_level);
}

void WebViewImplementationNative::set_has_focus(bool has_focus)
{
    client().async_set_has_focus(m_client_state.page_index, has_focus);
}

void WebViewImplementationNative::set_visible(bool visible)
{
    set_system_visibility_state(visible ? Web::HTML::VisibilityState::Visible : Web::HTML::VisibilityState::Hidden);
}

void WebViewImplementationNative::mouse_event(Web::MouseEvent::Type event_type, float x, float y, float raw_x, float raw_y, Web::UIEvents::MouseButton button, int wheel_delta_x, int wheel_delta_y)
{
    Gfx::IntPoint position = { x, y };
    Gfx::IntPoint screen_position = { raw_x, raw_y };

    auto buttons = Web::UIEvents::MouseButton::None;
    if (event_type == Web::MouseEvent::Type::MouseDown || (event_type == Web::MouseEvent::Type::MouseMove && button != Web::UIEvents::MouseButton::None))
        buttons = button;

    auto event = Web::MouseEvent {
        event_type,
        position.to_type<Web::DevicePixels>(),
        screen_position.to_type<Web::DevicePixels>(),
        button,
        buttons,
        Web::UIEvents::KeyModifier::Mod_None,
        wheel_delta_x,
        wheel_delta_y,
        nullptr
    };

    enqueue_input_event(move(event));
}

void WebViewImplementationNative::key_event(Web::KeyEvent::Type event_type, Web::UIEvents::KeyCode key, Web::UIEvents::KeyModifier modifiers, u32 code_point)
{
    auto event = Web::KeyEvent {
        event_type,
        key,
        modifiers,
        code_point,
        false,
        nullptr
    };

    enqueue_input_event(move(event));
}

NonnullRefPtr<WebView::WebContentClient> WebViewImplementationNative::bind_web_content_client()
{
    JavaEnvironment env(global_vm);

    int socket_fds[2] {};
    MUST(Core::System::socketpair(AF_LOCAL, SOCK_STREAM, 0, socket_fds));

    int ui_fd = socket_fds[0];
    int wc_fd = socket_fds[1];

    // NOTE: The java object takes ownership of the socket fds
    env.get()->CallVoidMethod(m_java_instance, s_methods.bind_webcontent, wc_fd);

    auto socket = MUST(Core::LocalSocket::adopt_fd(ui_fd));
    MUST(socket->set_blocking(true));

    auto new_client = make_ref_counted<WebView::WebContentClient>(make<IPC::Transport>(move(socket)), *this);

    return new_client;
}

}
