package com.zuehlke.carrera.javapilot.io;

import com.zuehlke.carrera.javapilot.akka.TrackPattern;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.AssertionErrors;

public class TrackPatternTest {

    @Test
    public void emptyInputTest() {
        Assert.assertEquals(TrackPattern.recognize(""), "");
    }

    @Test
    public void FifteenSectionsInputTest() {
        Assert.assertEquals(TrackPattern.recognize("LLLLLLLLLLLLLLL"), "");
    }

    @Test
    public void SixteenSectionsInputTest() {
        Assert.assertEquals(TrackPattern.recognize("LLLLLLLLLLLLLLLL"), "LLLLLLLL");
    }

    @Test
    public void DubaiSectionsInputTest() {
        Assert.assertEquals(TrackPattern.recognize("SRSRSLSRSRSRSRSLSRSRSR"), "SRSRSLSRSR");

    }

    @Test
    public void DocumentationSectionsInputTest() {
        Assert.assertEquals(TrackPattern.recognize("RSRSLSLSLSRSRSLSLSLSRSRS"), "RSRSLSLSLS");
    }
}
