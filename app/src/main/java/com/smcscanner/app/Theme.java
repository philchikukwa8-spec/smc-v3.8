package com.smcscanner.app;

/** Colours for the dark and light look. */
final class Theme {
    private Theme() {}

    static boolean dark = true;
    static int bg, card, tile, txt, dim, soft, chip, chipOk, chipOkTxt, arrow, green, red, accent, amber, grey, onColor;

    static void apply(boolean useDark) {
        dark = useDark;
        if (useDark) {
            bg = 0xFF021825; card = 0xFF0B2234; tile = 0xFF071C2B; txt = 0xFFECEFF1; dim = 0xFF90A4AE; soft = 0xFFB0BEC5;
            chip = 0xFF263340; chipOk = 0xFF1E4D49; chipOkTxt = 0xFF8FE3D8; arrow = 0xFF546E7A;
            green = 0xFF26A69A; red = 0xFFEF5350; accent = 0xFFE3B341; amber = 0xFFFFB74D; grey = 0xFF455A64; onColor = 0xFF021825;
        } else {
            bg = 0xFFF1F4F8; card = 0xFFFFFFFF; tile = 0xFFEDF1F5; txt = 0xFF1B2733; dim = 0xFF607D8B; soft = 0xFF455A64;
            chip = 0xFFE3E9EE; chipOk = 0xFFD3F0EC; chipOkTxt = 0xFF0B6B60; arrow = 0xFF90A4AE;
            green = 0xFF159488; red = 0xFFD9403B; accent = 0xFFA87400; amber = 0xFFE08A00; grey = 0xFFCFD8DC; onColor = 0xFFFFFFFF;
        }
    }
}
