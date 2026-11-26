/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <AK/String.h>
#include <Hub/HubClientEndpoint.h>

namespace Ladybird {

// What the Hub asks of the app as a whole, which LadybirdActivity.kt does.
struct AndroidApplication {
    static void open_url_in_new_tab(String const& url, bool activate, bool is_private);
    static void set_clipboard_text(String const&);
    static String clipboard_text();
    static void download_changed(Hub::DownloadState const&);
};

}
