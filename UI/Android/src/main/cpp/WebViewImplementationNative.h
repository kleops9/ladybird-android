/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <LibWebView/ViewImplementation.h>
#include <android/bitmap.h>
#include <android/native_window.h>
#include <jni.h>

namespace Ladybird {

class WebViewImplementationNative : public WebView::ViewImplementation {
public:
    // Creates a view with its own WebContent process.
    explicit WebViewImplementationNative(jobject thiz);
    // Creates a view for a page that already exists in the parent's WebContent process (window.open, target=_blank).
    WebViewImplementationNative(jobject thiz, WebViewImplementationNative& parent, u64 page_index);
    virtual ~WebViewImplementationNative() override
    {
        if (m_window)
            ANativeWindow_release(m_window);
    }

    virtual Web::DevicePixelSize viewport_size() const override { return m_viewport_size; }
    virtual Gfx::IntPoint to_content_position(Gfx::IntPoint p) const override { return p; }
    virtual Gfx::IntPoint to_widget_position(Gfx::IntPoint p) const override { return p; }
    virtual void update_zoom() override;

    NonnullRefPtr<WebView::WebContentClient> bind_web_content_client();

    virtual void initialize_client(CreateNewClient) override;

    void paint_into_bitmap(void* android_bitmap_raw, AndroidBitmapInfo const& info);

    // The window (from the Java view's SurfaceTexture) that frames are presented into. Takes ownership of a reference.
    void set_surface(ANativeWindow*);
    // While paused (during a pinch zoom), new frames aren't shown.
    void set_presentation_paused(bool);
    void present();

    void set_viewport_geometry(int w, int h);
    void set_device_pixel_ratio(float f);
    void set_has_focus(bool);
    void set_visible(bool);
    void set_private_browsing(bool);
    String cookies_for_url(URL::URL const&);

    void mouse_event(Web::MouseEvent::Type event_type, float x, float y, float raw_x, float raw_y, Web::UIEvents::MouseButton button, int wheel_delta_x, int wheel_delta_y);
    void key_event(Web::KeyEvent::Type event_type, Web::UIEvents::KeyCode key, Web::UIEvents::KeyModifier modifiers, u32 code_point);

    jobject java_instance() const { return m_java_instance; }

    static void cache_java_methods(JNIEnv*, jclass);

private:
    void install_callbacks();
    Gfx::Bitmap const* current_frame(Gfx::IntSize& painted_size) const;
    void copy_frame_to_rgba(u8* destination, size_t destination_pitch, int width, int height) const;

    jobject m_java_instance = nullptr;
    Web::DevicePixelSize m_viewport_size;
    ANativeWindow* m_window { nullptr };
    Gfx::IntSize m_window_buffer_size;
    bool m_presentation_paused { false };
};

}
