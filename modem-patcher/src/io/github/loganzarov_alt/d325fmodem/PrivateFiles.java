// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package io.github.loganzarov_alt.d325fmodem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keep files read by the app owned and labelled by the app, not by su. */
final class PrivateFiles {
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static File prepare(File cache, String name) throws PatchException {
        if (!name.matches("[A-Za-z0-9_.-]+") || name.contains("..")) {
            throw new PatchException("Invalid temporary file name");
        }
        File file = new File(cache, name);
        if (file.exists()) file.delete();
        try {
            create(file);
        } catch (IOException first) {
            // Older builds left root-created files with app_data_file:s0.
            // Remove only this cache file; recreate it from the app process.
            ModemOps.Shell cleanup = ModemOps.su("rm -f " + quote(file.getAbsolutePath()));
            if (cleanup.exit != 0) {
                throw new PatchException("Could not remove legacy temporary file: "
                        + file.getName() + "\n" + cleanup.out, first);
            }
            try {
                create(file);
            } catch (IOException second) {
                throw new PatchException("Could not create temporary file: "
                        + file.getName(), second);
            }
        }
        return file;
    }

    private static void create(File file) throws IOException {
        FileOutputStream out = new FileOutputStream(file, false);
        out.close();
    }

    static String baseband() {
        try {
            String text = ModemOps.su("getprop gsm.version.baseband\n"
                    + "getprop gsm.version.baseband1").out;
            Matcher match = Pattern.compile("[MP]8610B-[A-Za-z0-9_.-]+").matcher(text);
            if (match.find()) return match.group();
        } catch (PatchException ignored) {
        }
        return "?";
    }
}
