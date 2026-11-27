/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "SurfacePresenter.h"
#include <AK/Format.h>
#include <string.h>

namespace Ladybird {

SurfacePresenter::SurfacePresenter()
{
    if (int error = pthread_create(&m_thread, nullptr, thread_entry, this); error != 0)
        VERIFY_NOT_REACHED();
}

SurfacePresenter::~SurfacePresenter()
{
    pthread_mutex_lock(&m_mutex);
    m_stopping = true;
    pthread_cond_broadcast(&m_condition);
    pthread_mutex_unlock(&m_mutex);
    pthread_join(m_thread, nullptr);
    if (m_window)
        ANativeWindow_release(m_window);
}

void SurfacePresenter::set_window(ANativeWindow* window)
{
    if (window)
        ANativeWindow_acquire(window);
    pthread_mutex_lock(&m_mutex);
    auto* old_window = m_window;
    m_window = window;
    // A frame waiting was for the window before.
    m_has_pending = false;
    while (m_is_presenting)
        pthread_cond_wait(&m_condition, &m_mutex);
    pthread_mutex_unlock(&m_mutex);
    if (old_window)
        ANativeWindow_release(old_window);
}

u32* SurfacePresenter::begin_frame(Gfx::IntSize size)
{
    m_staging_size = size;
    m_staging.resize(static_cast<size_t>(size.width()) * size.height());
    return m_staging.data();
}

void SurfacePresenter::submit_frame()
{
    pthread_mutex_lock(&m_mutex);
    swap(m_staging, m_pending);
    swap(m_staging_size, m_pending_size);
    m_has_pending = true;
    pthread_cond_signal(&m_condition);
    pthread_mutex_unlock(&m_mutex);
}

void* SurfacePresenter::thread_entry(void* self)
{
    pthread_setname_np(pthread_self(), "SurfacePresent");
    static_cast<SurfacePresenter*>(self)->run();
    return nullptr;
}

void SurfacePresenter::run()
{
    pthread_mutex_lock(&m_mutex);
    while (true) {
        while (!m_stopping && !(m_has_pending && m_window))
            pthread_cond_wait(&m_condition, &m_mutex);
        if (m_stopping)
            break;

        swap(m_pending, m_working);
        auto size = m_pending_size;
        m_has_pending = false;
        auto* window = m_window;
        m_is_presenting = true;
        pthread_mutex_unlock(&m_mutex);

        if (window != m_configured_window || size != m_configured_size) {
            ANativeWindow_setBuffersGeometry(window, size.width(), size.height(), WINDOW_FORMAT_RGBA_8888);
            m_configured_window = window;
            m_configured_size = size;
        }
        ANativeWindow_Buffer buffer {};
        if (ANativeWindow_lock(window, &buffer, nullptr) == 0) {
            auto rows = min(size.height(), buffer.height);
            auto row_bytes = static_cast<size_t>(min(size.width(), buffer.width)) * sizeof(u32);
            auto* destination = static_cast<u8*>(buffer.bits);
            auto const* source = reinterpret_cast<u8 const*>(m_working.data());
            for (int y = 0; y < rows; ++y)
                memcpy(destination + static_cast<size_t>(y) * buffer.stride * sizeof(u32), source + static_cast<size_t>(y) * size.width() * sizeof(u32), row_bytes);
            ANativeWindow_unlockAndPost(window);
        }

        pthread_mutex_lock(&m_mutex);
        m_is_presenting = false;
        pthread_cond_broadcast(&m_condition);
    }
    pthread_mutex_unlock(&m_mutex);
}

}
