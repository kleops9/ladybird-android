/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <Hub/HubClientEndpoint.h>
#include <Hub/HubServerEndpoint.h>
#include <HubClientDefaults.h>
#include <LibIPC/ConnectionToServer.h>

namespace Ladybird {

// The Android UI's end of its connection to the Hub, the process that holds the browser's state and runs its helper
// processes. Messages the UI doesn't act on fall through to HubClientDefaults.
class HubConnection final
    : public IPC::ConnectionToServer<HubClientEndpoint, HubServerEndpoint, HubClientDefaults> {
    C_OBJECT_ABSTRACT(HubConnection)

public:
    explicit HubConnection(NonnullOwnPtr<IPC::Transport>);
    virtual ~HubConnection() override;

    static HubConnection& the();
    static HubConnection* the_if_connected();

    // Starts the Hub from the APK's native library directory and connects to it.
    static ErrorOr<NonnullRefPtr<HubConnection>> launch(ByteString const& native_library_directory, Hub::UIInfo const&, Vector<ByteString> extra_arguments);

    Hub::InitialState const& initial_state() const { return m_initial_state; }

    // The Hub's settings as JSON, as of its last word on them.
    ByteString const& settings_json() const { return m_settings_json; }

private:
    virtual void die() override;

    virtual void settings_changed(ByteString, u64) override;
    virtual void app_open_url_in_new_tab(URL::URL, Web::HTML::ActivateTab, Optional<u64>, WebView::IsPrivate) override;
    virtual void app_open_view_in_new_tab(u64, Optional<u64>, WebView::IsPrivate, Web::HTML::ActivateTab) override;
    virtual void app_set_clipboard_text(String, WebView::Application::ClipboardType) override;
    virtual void app_read_clipboard_text(u64, WebView::Application::ClipboardType) override;
    virtual void download_added(Hub::DownloadState) override;
    virtual void download_updated(Hub::DownloadState) override;

    virtual void view_install_backing_stores(u64, u64, Vector<i32>, Vector<Gfx::SharedImage>) override;
    virtual void view_add_backing_stores(u64, Vector<i32>, Vector<Gfx::SharedImage>) override;
    virtual void view_retire_backing_stores(u64, Vector<i32>) override;
    virtual void view_present_backing_store(u64, u64, i32, Gfx::IntSize, double) override;
    virtual void view_accept_presented_backing_store(u64, i32, Gfx::IntRect) override;
    virtual void view_keep_front_backing_store_as_backup(u64) override;
    virtual void view_clear_backup_backing_store(u64) override;
    virtual void view_clear_backing_stores(u64) override;

    virtual void view_state_changed(u64, Hub::ViewState) override;
    virtual void view_load_start(u64) override;
    virtual void view_load_finish(u64, URL::URL) override;
    virtual void view_title_change(u64, Utf16String) override;
    virtual void view_url_change(u64, URL::URL) override;
    virtual void view_loading_state_change(u64, bool) override;
    virtual void view_favicon_change(u64, Optional<Gfx::ShareableBitmap>) override;
    virtual void view_theme_color_change(u64, Gfx::Color) override;
    virtual void view_scrolled_to_top_change(u64, bool) override;
    virtual void view_input_method_state_change(u64) override;
    virtual void view_open_new_view(u64, u64, Hub::ViewState, Web::HTML::ActivateTab, Web::HTML::WebViewHints) override;
    virtual void view_activate_tab(u64) override;
    virtual void view_close(u64) override;
    virtual void view_request_context_menu(u64, WebView::ContextMenuRequest) override;
    virtual void view_request_alert(u64, Utf16String) override;
    virtual void view_request_confirm(u64, Utf16String) override;
    virtual void view_request_prompt(u64, Utf16String, Utf16String) override;
    virtual void view_request_select_dropdown(u64, Gfx::IntPoint, i32, Vector<Web::HTML::SelectItem>) override;
    virtual void view_find_in_page(u64, size_t, Optional<size_t>) override;
    virtual void view_web_content_crashed(u64, WebView::ViewImplementation::WebContentCrashReason) override;

    Hub::InitialState m_initial_state;
    ByteString m_settings_json;
    bool m_did_initialize { false };
};

}
