/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include "SurfacePresenter.h"
#include <AK/HashMap.h>
#include <AK/Optional.h>
#include <Hub/HubClientEndpoint.h>
#include <LibGfx/Size.h>
#include <LibWebCommon/Page/InputEvent.h>
#include <LibWebView/PageBackingStores.h>
#include <android/native_window.h>
#include <jni.h>

namespace Ladybird {

// The UI's side of a tab. The view itself lives in the Hub: this shows its frames in the tab's Surface, sends it the
// user's input, and passes what the Hub says about it on to the Kotlin WebViewImplementation.
class RemoteViewAndroid {
public:
    static void cache_java_methods(JNIEnv*, jclass);
    static RemoteViewAndroid* find(u64 view_id);

    // Gives the views the dark or the light system theme, and the ones made later too.
    static void set_websites_prefer_dark(bool);

    // Makes a new view in the Hub, or with a view ID, shows one that the Hub made (as for window.open()).
    RemoteViewAndroid(jobject java_instance, WebView::IsPrivate, Optional<u64> existing_view_id, double device_pixel_ratio, double refresh_rate);
    ~RemoteViewAndroid();

    u64 view_id() const { return m_view_id; }
    Hub::ViewState const& state() const { return m_state; }

    // Calls from Kotlin.
    void load(StringView url);
    void reload();
    void stop_loading();
    void traverse_history(int delta);
    void zoom(int direction);
    void pinch(float focus_x, float focus_y, float scale_delta, bool continues_gesture);
    void find_in_page(Utf16String const& query);
    void find_in_page_next();
    void find_in_page_previous();
    void find_in_page_end();
    void alert_closed();
    void confirm_closed(bool accepted);
    void prompt_closed(Optional<Utf16String> response);
    void select_dropdown_closed(Optional<u32> item_id);
    void commit_text(Utf16String const& text);

    void set_surface(ANativeWindow*);
    void set_presentation_paused(bool);
    void set_viewport_size(int width, int height);
    void set_device_pixel_ratio(double);
    void set_has_focus(bool);
    void set_visible(bool);

    void mouse_event(Web::MouseEvent::Type, float x, float y, float screen_x, float screen_y, Web::UIEvents::MouseButton, int wheel_delta_x, int wheel_delta_y, bool is_touch, int click_count);
    void scroll_gesture(Web::ScrollGesturePhase, float x, float y, float screen_x, float screen_y, double delta_x, double delta_y);
    void key_event(Web::KeyEvent::Type, Web::UIEvents::KeyCode, Web::UIEvents::KeyModifier, u32 code_point);

    // Copies the frame on display into RGBA memory, such as an Android bitmap. Returns false if there is none yet.
    bool copy_frame_to_rgba(u8* destination, size_t destination_pitch, int width, int height) const;

    // Messages from the Hub.
    void did_install_backing_stores(u64 page_id, Vector<i32> bitmap_ids, Vector<Gfx::SharedImage>);
    void did_add_backing_stores(Vector<i32> bitmap_ids, Vector<Gfx::SharedImage>);
    void did_retire_backing_stores(Vector<i32> bitmap_ids);
    void did_present_backing_store(u64 page_id, i32 bitmap_id, Gfx::IntSize, double device_pixel_ratio);
    void did_accept_presented_backing_store();
    void keep_front_backing_store_as_backup() { m_backing_stores.keep_front_as_backup(); }
    void clear_backup_backing_store() { m_backing_stores.clear_backup(); }
    void clear_backing_stores() { m_backing_stores.clear(); }

    void did_change_state(Hub::ViewState);
    void did_start_loading();
    void did_finish_loading(URL::URL const&);
    void did_change_title(Utf16String const&);
    void did_change_url(URL::URL const&);
    void did_change_favicon(Optional<Gfx::ShareableBitmap> const&);
    void did_change_theme_color(Gfx::Color);
    void did_change_scrolled_to_top(bool);
    void did_change_input_method_state();
    String did_request_new_view(u64 new_view_id, Web::HTML::ActivateTab);
    void did_request_activation();
    void did_request_close();
    void did_request_context_menu(WebView::ContextMenuRequest const&);
    void did_request_alert(Utf16String const&);
    void did_request_confirm(Utf16String const&);
    void did_request_prompt(Utf16String const&, Utf16String const& default_value);
    void did_request_select_dropdown(Vector<Web::HTML::SelectItem> const&);
    void did_find_in_page(size_t current_match_index, Optional<size_t> total_match_count);
    void did_crash();

private:
    void present();
    void release_backing_store(i32 bitmap_id);
    void call_java(jmethodID, ...) const;
    void call_java_with_string(jmethodID, StringView) const;

    jobject m_java_instance { nullptr };
    u64 m_view_id { 0 };
    WebView::IsPrivate m_is_private { WebView::IsPrivate::No };
    Hub::ViewState m_state;

    WebView::PageBackingStores m_backing_stores;
    Optional<u64> m_backing_stores_page_id;

    ANativeWindow* m_window { nullptr };
    SurfacePresenter m_presenter;
    Gfx::IntSize m_viewport_size;
    double m_device_pixel_ratio { 1.0 };
    bool m_presentation_paused { false };
    bool m_input_method_enabled { false };
    u64 m_next_input_id { 1 };
};

}
