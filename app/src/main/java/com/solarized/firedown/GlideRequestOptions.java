package com.solarized.firedown;

import com.bumptech.glide.load.Option;
import com.solarized.firedown.utils.FileUriHelper;

public class GlideRequestOptions {

    public static final Option<String> MIMETYPE = Option.memory("mimetype", FileUriHelper.MIMETYPE_BINARY_OCTET);

    public static final Option<String> FILEPATH = Option.memory("filepath", "");

    public static final Option<Long> LENGTH = Option.memory("long", 0L);

    public static final Option<Long> FRAME = Option.memory("frame", 0L);

    public static final Option<String> HEADERS = Option.memory("headers", "");

    public static final Option<String> KEY = Option.memory("key", "");
}
