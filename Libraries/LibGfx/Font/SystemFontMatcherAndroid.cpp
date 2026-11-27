/*
 * Copyright (c) 2026-present, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include <AK/ByteString.h>
#include <AK/NeverDestroyed.h>
#include <AK/Vector.h>
#include <LibGfx/Font/SystemFontMatcher.h>
#include <android/font.h>
#include <android/system_fonts.h>

// Android lists its fonts in /system/etc/fonts.xml, in the order that it falls back through them, and the NDK reads that
// list for us. AFontMatcher would match code points the way Android does, but it needs the fonts that the Java side of an
// app registers, and the processes that match fonts here are not Java processes.
namespace Gfx::SystemFontMatcher {

namespace {

struct Face {
    ByteString path;
    u32 ttc_index { 0 };
};

// A face of a family at one style. The faces of a variable font are one face at many styles.
struct Entry {
    size_t face { 0 };
    u16 weight { 0 };
    bool italic { false };
    ByteString locale;
};

struct SystemFonts {
    Vector<Face> faces;
    Vector<Entry> entries;
};

SystemFonts load_system_fonts()
{
    SystemFonts fonts;
    auto* iterator = ASystemFontIterator_open();
    if (!iterator)
        return fonts;
    while (auto* font = ASystemFontIterator_next(iterator)) {
        ByteString path { AFont_getFontFilePath(font) };
        auto ttc_index = static_cast<u32>(AFont_getCollectionIndex(font));
        auto face = fonts.faces.find_first_index_if([&](Face const& face) { return face.ttc_index == ttc_index && face.path == path; });
        if (!face.has_value()) {
            face = fonts.faces.size();
            fonts.faces.append({ .path = move(path), .ttc_index = ttc_index });
        }
        auto const* locale = AFont_getLocale(font);
        fonts.entries.append({
            .face = *face,
            .weight = AFont_getWeight(font),
            .italic = AFont_isItalic(font),
            .locale = locale ? ByteString { locale } : ByteString {},
        });
        AFont_close(font);
    }
    ASystemFontIterator_close(iterator);
    return fonts;
}

SystemFonts const& system_fonts()
{
    static NeverDestroyed<SystemFonts> fonts { load_system_fonts() };
    return *fonts;
}

Optional<SystemFontFile> loadable_face(Face const& face)
{
    return loadable_file(face.path, face.ttc_index);
}

bool face_covers(Face const& face, u32 code_point)
{
    auto file = loadable_face(face);
    if (!file.has_value())
        return false;
    auto typeface = load(*file);
    return !typeface.is_error() && typeface.value()->glyph_id_for_code_point(code_point) != 0;
}

}

Optional<SystemFontMatch> match_family_style(StringView, u16, u16, u8)
{
    // The font catalog holds every family in the font directories by its own name.
    return {};
}

Optional<SystemFontMatch> match_code_point(u32 code_point, u16 weight, u16, u8 slope, bool prefer_color_emoji)
{
    auto const& fonts = system_fonts();
    Vector<Optional<bool>> coverage;
    coverage.resize(fonts.faces.size());
    auto covers = [&](Entry const& entry) {
        auto& covered = coverage[entry.face];
        if (!covered.has_value())
            covered = face_covers(fonts.faces[entry.face], code_point);
        return *covered;
    };

    // The fonts of one family share a locale and follow each other. Of the faces of the first family that has the code
    // point, take the one closest to the style.
    auto closest_face_in_family = [&](size_t index) -> Optional<SystemFontMatch> {
        auto const& locale = fonts.entries[index].locale;
        bool wants_italic = slope != 0;
        Optional<size_t> best_face;
        u32 best_distance = NumericLimits<u32>::max();
        for (auto i = index; i < fonts.entries.size() && fonts.entries[i].locale == locale; ++i) {
            auto const& entry = fonts.entries[i];
            if (!covers(entry))
                continue;
            u32 distance = (entry.italic == wants_italic ? 0 : 1000) + abs(static_cast<int>(entry.weight) - static_cast<int>(weight));
            if (distance < best_distance) {
                best_distance = distance;
                best_face = entry.face;
            }
        }
        if (!best_face.has_value())
            return {};
        auto file = loadable_face(fonts.faces[*best_face]);
        if (!file.has_value())
            return {};
        return SystemFontMatch { file.release_value() };
    };

    // The script subtag Zsye marks the color emoji fonts.
    if (prefer_color_emoji) {
        for (size_t i = 0; i < fonts.entries.size(); ++i) {
            if (fonts.entries[i].locale.contains("Zsye"sv) && covers(fonts.entries[i]))
                return closest_face_in_family(i);
        }
    }

    for (size_t i = 0; i < fonts.entries.size(); ++i) {
        if (covers(fonts.entries[i]))
            return closest_face_in_family(i);
    }
    return {};
}

Optional<FlyString> resolve_generic_family(StringView, u16, u8)
{
    // FIXME: fonts.xml names the families of 'sans-serif', 'serif' and 'monospace', but the NDK leaves the names out.
    //        Until then, the font plugin picks them from its own list, in which Android's are.
    return {};
}

}
