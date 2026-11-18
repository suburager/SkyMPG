package org.mpgram.droid;

/** Готовые публичные инстансы MPGram. Список можно расширять. */
public class Instances {

    public static final String[] HOSTS = new String[] {
            "mp.nnproject.cc",
            "mp2.nnproject.cc",
            "mp.wunderwungiel.pl"
    };

    /** Адрес инстанса вида "http://host/". По умолчанию HTTP: на старых Android нет современного TLS. */
    public static String urlFor(String host, boolean https) {
        return (https ? "https://" : "http://") + host + "/";
    }
}
