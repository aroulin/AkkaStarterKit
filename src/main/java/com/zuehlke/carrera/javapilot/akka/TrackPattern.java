package com.zuehlke.carrera.javapilot.akka;

public class TrackPattern {
    public static String recognize(String input) {
        // Find shortest common sub-sequence in input of size at least 8

        for(int i = 8; i <= input.length()/2; i++) {
            if (input.substring(0, i).equals(input.substring(i, 2*i)))
                return input.substring(0, i);
        }

        return "";
    }
}
