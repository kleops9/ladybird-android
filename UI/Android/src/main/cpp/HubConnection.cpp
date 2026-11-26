/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "HubConnection.h"
#include "AndroidApplication.h"
#include "RemoteViewAndroid.h"
#include <AK/Hex.h>
#include <LibCore/Environment.h>
#include <LibCore/Process.h>
#include <LibCore/System.h>
#include <LibIPC/Encoder.h>
#include <LibIPC/Transport.h>

namespace Ladybird {

static HubConnection* s_the;

HubConnection::HubConnection(NonnullOwnPtr<IPC::Transport> transport)
    : IPC::ConnectionToServer<HubClientEndpoint, HubServerEndpoint, HubClientDefaults>(*this, move(transport))
{
    s_the = this;
}

HubConnection::~HubConnection()
{
    if (s_the == this)
        s_the = nullptr;
}

HubConnection& HubConnection::the()
{
    VERIFY(s_the);
    return *s_the;
}

HubConnection* HubConnection::the_if_connected()
{
    return s_the;
}

ErrorOr<NonnullRefPtr<HubConnection>> HubConnection::launch(ByteString const& native_library_directory, Hub::UIInfo const& ui_info, Vector<ByteString> extra_arguments)
{
    auto hub_path = ByteString::formatted("{}/libHub.so", native_library_directory);

    IPC::MessageBuffer ui_info_buffer;
    IPC::Encoder encoder { ui_info_buffer };
    TRY(encoder.encode(ui_info));

    Vector<ByteString> arguments;
    arguments.append(ByteString::formatted("--ui-info={}", encode_hex(ui_info_buffer.data().span())));
    arguments.append(ByteString::formatted("--ui-pid={}", Core::System::getpid()));
    // Android starts apps in a seccomp sandbox of its own, and app processes can't make the namespaces that the Linux
    // sandbox uses.
    arguments.append("--disable-sandbox"sv);
    arguments.extend(move(extra_arguments));

    Core::ProcessSpawnOptions spawn_options {
        .name = "Hub"sv,
        .executable = hub_path,
        .die_with_parent = true,
        .arguments = arguments,
    };

    // An app's standard output and error go nowhere, and Rust writes the message of a panic there. Keep what the Hub and
    // its helpers write in a log file in the cache directory instead (adb shell run-as <package> cat cache/ladybird.log).
    if (auto const* cache_directory = getenv("XDG_CACHE_HOME")) {
        auto log_path = ByteString::formatted("{}/ladybird.log", cache_directory);
        spawn_options.file_actions.append(Core::FileAction::OpenFile { .path = log_path, .mode = Core::File::OpenMode::Write | Core::File::OpenMode::Truncate, .fd = STDOUT_FILENO });
        spawn_options.file_actions.append(Core::FileAction::OpenFile { .path = log_path, .mode = Core::File::OpenMode::Write | Core::File::OpenMode::Append, .fd = STDERR_FILENO });
    }

    int socket_fds[2] {};
    TRY(Core::System::socketpair(AF_LOCAL, SOCK_STREAM | SOCK_CLOEXEC, 0, socket_fds));
    spawn_options.file_actions.append(Core::FileAction::DupFd { .write_fd = socket_fds[1], .fd = socket_fds[1] });
    TRY(Core::Environment::set("SOCKET_TAKEOVER"sv, ByteString::formatted("ipc:{}", socket_fds[1]), Core::Environment::Overwrite::Yes));
    auto process = Core::Process::spawn(spawn_options);
    (void)Core::Environment::unset("SOCKET_TAKEOVER"sv);
    TRY(Core::System::close(socket_fds[1]));
    if (process.is_error()) {
        (void)Core::System::close(socket_fds[0]);
        return process.release_error();
    }

    auto transport = TRY(IPC::Transport::adopt_fd(socket_fds[0]));
    auto connection = adopt_ref(*new HubConnection(move(transport)));

    auto state = connection->try_initialize();
    if (state.is_error())
        return Error::from_string_literal("The Hub ended before it answered");
    connection->m_initial_state = state.release_value();
    connection->m_did_initialize = true;
    connection->set_peer_owns_this_process(true);
    connection->async_ui_did_start();
    return connection;
}

void HubConnection::die()
{
    // Every tab lives in the Hub, so there's nothing to show without it. Android restarts the app when it's opened again.
    warnln("Lost the connection to the Hub");
    if (m_did_initialize)
        Core::Process::terminate_immediately(1);
}

void HubConnection::app_open_url_in_new_tab(URL::URL url, Web::HTML::ActivateTab activate_tab, Optional<u64>, WebView::IsPrivate is_private)
{
    AndroidApplication::open_url_in_new_tab(url.to_string(), activate_tab == Web::HTML::ActivateTab::Yes, is_private == WebView::IsPrivate::Yes);
}

void HubConnection::app_open_view_in_new_tab(u64 view_id, Optional<u64> source_view_id, WebView::IsPrivate, Web::HTML::ActivateTab activate_tab)
{
    if (source_view_id.has_value()) {
        if (auto* source = RemoteViewAndroid::find(*source_view_id)) {
            source->did_request_new_view(view_id, activate_tab);
            return;
        }
    }
    warnln("Hub asked for a tab for view {}, which no tab asked for", view_id);
}

void HubConnection::app_set_clipboard_text(String text, WebView::Application::ClipboardType)
{
    AndroidApplication::set_clipboard_text(text);
}

void HubConnection::app_read_clipboard_text(u64 request_id, WebView::Application::ClipboardType)
{
    async_answer_clipboard_text(request_id, Utf16String::from_utf8(AndroidApplication::clipboard_text()));
}

void HubConnection::download_added(Hub::DownloadState download)
{
    AndroidApplication::download_changed(download);
}

void HubConnection::download_updated(Hub::DownloadState download)
{
    AndroidApplication::download_changed(download);
}

#define FOR_VIEW(view_id, action)                       \
    do {                                                \
        if (auto* view = RemoteViewAndroid::find(view_id)) \
            view->action;                               \
    } while (0)

void HubConnection::view_install_backing_stores(u64 view_id, u64 page_id, Vector<i32> bitmap_ids, Vector<Gfx::SharedImage> backing_stores)
{
    if (auto* view = RemoteViewAndroid::find(view_id)) {
        view->did_install_backing_stores(page_id, move(bitmap_ids), move(backing_stores));
        return;
    }
    // A view that's gone will never show these, so the front backing store goes straight back.
    if (!bitmap_ids.is_empty())
        async_release_backing_store(page_id, bitmap_ids[0]);
}

void HubConnection::view_add_backing_stores(u64 view_id, Vector<i32> bitmap_ids, Vector<Gfx::SharedImage> backing_stores)
{
    FOR_VIEW(view_id, did_add_backing_stores(move(bitmap_ids), move(backing_stores)));
}

void HubConnection::view_retire_backing_stores(u64 view_id, Vector<i32> bitmap_ids)
{
    FOR_VIEW(view_id, did_retire_backing_stores(move(bitmap_ids)));
}

void HubConnection::view_present_backing_store(u64 view_id, u64 page_id, i32 bitmap_id, Gfx::IntSize size, double device_pixel_ratio)
{
    if (auto* view = RemoteViewAndroid::find(view_id)) {
        view->did_present_backing_store(page_id, bitmap_id, size, device_pixel_ratio);
        return;
    }
    async_release_backing_store(page_id, bitmap_id);
}

void HubConnection::view_accept_presented_backing_store(u64 view_id, i32, Gfx::IntRect)
{
    FOR_VIEW(view_id, did_accept_presented_backing_store());
}

void HubConnection::view_keep_front_backing_store_as_backup(u64 view_id)
{
    FOR_VIEW(view_id, keep_front_backing_store_as_backup());
}

void HubConnection::view_clear_backup_backing_store(u64 view_id)
{
    FOR_VIEW(view_id, clear_backup_backing_store());
}

void HubConnection::view_clear_backing_stores(u64 view_id)
{
    FOR_VIEW(view_id, clear_backing_stores());
}

void HubConnection::view_state_changed(u64 view_id, Hub::ViewState state)
{
    FOR_VIEW(view_id, did_change_state(move(state)));
}

void HubConnection::view_load_start(u64 view_id)
{
    FOR_VIEW(view_id, did_start_loading());
}

void HubConnection::view_load_finish(u64 view_id, URL::URL url)
{
    FOR_VIEW(view_id, did_finish_loading(url));
}

void HubConnection::view_title_change(u64 view_id, Utf16String title)
{
    FOR_VIEW(view_id, did_change_title(title));
}

void HubConnection::view_url_change(u64 view_id, URL::URL url)
{
    FOR_VIEW(view_id, did_change_url(url));
}

void HubConnection::view_loading_state_change(u64, bool)
{
    // view_load_start() and view_load_finish() say the same, with more detail.
}

void HubConnection::view_favicon_change(u64 view_id, Optional<Gfx::ShareableBitmap> favicon)
{
    FOR_VIEW(view_id, did_change_favicon(favicon));
}

void HubConnection::view_theme_color_change(u64 view_id, Gfx::Color color)
{
    FOR_VIEW(view_id, did_change_theme_color(color));
}

void HubConnection::view_input_method_state_change(u64 view_id)
{
    // The new state arrives in a view_state_changed() right before this.
    FOR_VIEW(view_id, did_change_input_method_state());
}

void HubConnection::view_open_new_view(u64 view_id, u64 new_view_id, Hub::ViewState, Web::HTML::ActivateTab activate_tab, Web::HTML::WebViewHints)
{
    if (auto* view = RemoteViewAndroid::find(view_id)) {
        view->did_request_new_view(new_view_id, activate_tab);
        return;
    }
    async_destroy_view(new_view_id);
}

void HubConnection::view_activate_tab(u64 view_id)
{
    FOR_VIEW(view_id, did_request_activation());
}

void HubConnection::view_close(u64 view_id)
{
    FOR_VIEW(view_id, did_request_close());
}

void HubConnection::view_request_context_menu(u64 view_id, WebView::ContextMenuRequest request)
{
    FOR_VIEW(view_id, did_request_context_menu(request));
}

void HubConnection::view_request_alert(u64 view_id, Utf16String message)
{
    FOR_VIEW(view_id, did_request_alert(message));
}

void HubConnection::view_request_confirm(u64 view_id, Utf16String message)
{
    FOR_VIEW(view_id, did_request_confirm(message));
}

void HubConnection::view_request_prompt(u64 view_id, Utf16String message, Utf16String default_value)
{
    FOR_VIEW(view_id, did_request_prompt(message, default_value));
}

void HubConnection::view_request_select_dropdown(u64 view_id, Gfx::IntPoint, i32, Vector<Web::HTML::SelectItem> items)
{
    FOR_VIEW(view_id, did_request_select_dropdown(items));
}

void HubConnection::view_find_in_page(u64 view_id, size_t current_match_index, Optional<size_t> total_match_count)
{
    FOR_VIEW(view_id, did_find_in_page(current_match_index, total_match_count));
}

void HubConnection::view_web_content_crashed(u64 view_id, WebView::ViewImplementation::WebContentCrashReason)
{
    FOR_VIEW(view_id, did_crash());
}

}
