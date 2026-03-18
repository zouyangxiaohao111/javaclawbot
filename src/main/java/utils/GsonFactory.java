package utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.LocalDateTime;

public final class GsonFactory {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeTypeAdapter())
            .disableHtmlEscaping()
            .create();

    private GsonFactory() {}

    public static Gson getGson() {
        return GSON;
    }
}