package org.mpgram.droid;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;

/** Несколько файлов из одного выбора в галерее (Android 4.3+). Класс грузится только там. */
public class MultiPick {

    /** Все выбранные Uri или null, если выбран один файл (он лежит в data.getData()). */
    public static ArrayList<Uri> uris(Intent data) {
        ClipData clip = data.getClipData();
        if (clip == null || clip.getItemCount() < 2) {
            return null;
        }
        ArrayList<Uri> out = new ArrayList<Uri>();
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri u = clip.getItemAt(i).getUri();
            if (u != null) {
                out.add(u);
            }
        }
        return out;
    }
}
