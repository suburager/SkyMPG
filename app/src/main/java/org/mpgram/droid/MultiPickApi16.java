package org.mpgram.droid;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;

/** ClipData (API 11, множественный выбор из галереи с API 16). Грузится только там. */
class MultiPickApi16 {

    static ArrayList<Uri> uris(Intent data) {
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
