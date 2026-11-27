/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <AK/Noncopyable.h>
#include <AK/Types.h>
#include <AK/Vector.h>
#include <LibGfx/Size.h>
#include <android/native_window.h>
#include <pthread.h>

namespace Ladybird {

// Puts frames into a tab's window on a thread of its own. Getting a buffer from the window waits until the display is
// done with one, which would otherwise hold up the main thread, and with it touch input, on every frame. The main thread
// copies each frame out of the Compositor's backing store into a buffer of this, so that backing store goes back to the
// Compositor as soon as it's replaced. A frame that comes while the thread is still putting the one before into the
// window takes the place of any frame waiting, so the window shows the newest.
class SurfacePresenter {
    AK_MAKE_NONCOPYABLE(SurfacePresenter);
    AK_MAKE_NONMOVABLE(SurfacePresenter);

public:
    SurfacePresenter();
    ~SurfacePresenter();

    // Takes a reference to the window, and stops using the one before before this returns.
    void set_window(ANativeWindow*);

    // A buffer of RGBA pixels, rows of `size.width()` without padding, for the main thread to fill before submit_frame().
    u32* begin_frame(Gfx::IntSize size);
    void submit_frame();

private:
    static void* thread_entry(void*);
    void run();

    pthread_t m_thread {};
    pthread_mutex_t m_mutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_cond_t m_condition = PTHREAD_COND_INITIALIZER;

    // Guarded by m_mutex.
    ANativeWindow* m_window { nullptr };
    Vector<u32> m_pending;
    Gfx::IntSize m_pending_size;
    bool m_has_pending { false };
    bool m_is_presenting { false };
    bool m_stopping { false };

    // The main thread's.
    Vector<u32> m_staging;
    Gfx::IntSize m_staging_size;

    // The presenting thread's.
    Vector<u32> m_working;
    ANativeWindow* m_configured_window { nullptr };
    Gfx::IntSize m_configured_size;
};

}
