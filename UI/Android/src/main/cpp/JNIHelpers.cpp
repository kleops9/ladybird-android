/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "JNIHelpers.h"
#include <AK/UnicodeUtils.h>
#include <AK/Vector.h>

namespace Ladybird {

jstring JavaEnvironment::jstring_from_ak_string(String const& str)
{
    Vector<jchar> code_units;
    code_units.ensure_capacity(str.bytes().size());
    for (auto code_point : str.code_points())
        (void)AK::UnicodeUtils::code_point_to_utf16(code_point, [&](auto code_unit) { code_units.append(static_cast<jchar>(code_unit)); });
    return m_env->NewString(code_units.data(), code_units.size());
}

}
