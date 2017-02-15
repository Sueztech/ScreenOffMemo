package com.sueztech.screenoffmemo;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

class ActionMemo {

    private static final SimpleDateFormat dateParser = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private Date date;
    private final String name;

    ActionMemo(File f) {
        name = f.getName();
        try {
            date = dateParser.parse(name.replace("ActionMemo_", "").replace(".spd", ""));
        } catch (ParseException e) {
            date = null;
            e.printStackTrace();
        }
    }

    String getName() {
        return name;
    }

    @Override
    public String toString() {
        return date.toString();
    }

}