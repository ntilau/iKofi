package com.ikofi.sshd;

import java.util.List;

import com.ikofi.sshd.settings.Prefs;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UserArgsTest {

    @Test
    public void a() {
        final String args = "-x -p 6 -p 1234:88 " +
                            "\n-u 7 -o -p   5678:9999 ";

        final List<String> options = Prefs.splitOptions(args);
        final List<String> list = Prefs.collectBindings(options);

        assertEquals(3, list.size());
        assertEquals("6", list.get(0));
        assertEquals("1234:88", list.get(1));
        assertEquals("5678:9999", list.get(2));
    }
}
