package com.zuehlke.carrera.javapilot.akka;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import com.zuehlke.carrera.relayapi.messages.PenaltyMessage;
import com.zuehlke.carrera.relayapi.messages.RaceStartMessage;
import com.zuehlke.carrera.relayapi.messages.SensorEvent;
import com.zuehlke.carrera.timeseries.FloatingHistory;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;

/**
 * this logic node increases the power level by 10 units per 0.5 second until it receives a penalty
 * then reduces by ten units.
 */
public class PowerUpUntilPenalty extends UntypedActor {

    private final ActorRef kobayashi;

    private double currentPower = 0;

    private SECTION_E currentSection = SECTION_E.STILL_STANDING;

    private String track = "";

    // For getting our way after a stop
    private int myPositionOnTheTrackIs = 0;
    private String track_since_lost = "";
    private boolean is_lost = true;

    private boolean newSection = false;

    private String history = "";

    private boolean probing = true;

    private long lastIncrease = 0;

    private boolean weDiscoveredTheMap = false;

    // VERY IMPORTANT: TWEAK THEM FOR REAL TRACK OR SIMULATOR
    private final int INITIAL_POWER = 100;

    private final int MAX_POWER = 160;

    private final int LONG_STRAIGHT_THRESHOLD = 200;

    private final int LONG_STRAIGHT_PREFERRED_POWER = 140;

    private int S_cnt = 0;

    private int maxNbLastGyrozValues = 10;

    private FloatingHistory gyrozHistory = new FloatingHistory(8);

    private ArrayList<Double> lastGyrozValues = new ArrayList<>();

    enum SECTION_E {
        STILL_STANDING,
        STRAIGHT,
        LEFT_CURVE,
        RIGHT_CURVE
    }

    private ArrayList<Section> map = new ArrayList<Section>();
    private ArrayList<Integer> S_cnts = new ArrayList<Integer>();

    public class Section {
        Character type; // L, R or S
        int preferredPower = INITIAL_POWER;
        int S_cnt = 0;
    }

    /**
     * @param pilotActor The central pilot actor
     * @param duration   the period between two increases
     * @return the actor props
     */
    public static Props props(ActorRef pilotActor, int duration) {
        return Props.create(
                PowerUpUntilPenalty.class, () -> new PowerUpUntilPenalty(pilotActor, duration));
    }

    private final int duration;

    public PowerUpUntilPenalty(ActorRef pilotActor, int duration) {
        lastIncrease = System.currentTimeMillis();
        this.kobayashi = pilotActor;
        this.duration = duration;
    }


    @Override
    public void onReceive(Object message) throws Exception {

        if (message instanceof SensorEvent) {
            handleSensorEvent((SensorEvent) message);

        } else if (message instanceof PenaltyMessage) {
            handlePenaltyMessage();

        } else if (message instanceof RaceStartMessage) {
            handleRaceStart();

        } else {
            unhandled(message);
        }
    }

    private void handleRaceStart() {
        currentPower = 0;
        gyrozHistory = new FloatingHistory(8);
        currentSection = SECTION_E.STILL_STANDING;
        newSection = false;
        weDiscoveredTheMap = false;
        lastGyrozValues.clear();
        lastIncrease = 0;
        probing = true;
        track = "";
    }

    private void handlePenaltyMessage() {
        currentPower -= 10;
        System.out.println("PENALTY");
        kobayashi.tell(new PowerAction((int) currentPower), getSelf());
        probing = false;

        if (weDiscoveredTheMap) {
            int pos = myPositionOnTheTrackIs - 1;
            if (pos < 0)
                pos = map.size() - 1;
            map.get(pos).preferredPower -= 10;
        }
    }

    private boolean isLeftCurve(ArrayList<Double> lastValues) {
        if (lastValues.size() < maxNbLastGyrozValues)
            return false;

        if (currentSection == SECTION_E.LEFT_CURVE || currentSection == SECTION_E.RIGHT_CURVE)
            return false;

        for (Double f : lastValues) {
            if (f >= -500)
                return false;
        }

        return true;
    }

    private boolean isRightCurve(ArrayList<Double> lastValues) {
        if (lastValues.size() < maxNbLastGyrozValues)
            return false;

        if (currentSection == SECTION_E.RIGHT_CURVE || currentSection == SECTION_E.LEFT_CURVE)
            return false;

        for (Double f : lastValues) {
            if (f <= 500)
                return false;
        }

        return true;
    }

    private boolean isStraight(ArrayList<Double> lastValues) {
        if (lastValues.size() < maxNbLastGyrozValues)
            return false;

        if (currentSection == SECTION_E.STRAIGHT){
            S_cnt++;
            return false;
        }

        for (Double f : lastValues) {
            if (f < -500 || f > 500)
                return false;
        }

        return true;
    }


    /**
     * Strategy: increase quickly when standing still to overcome haptic friction
     * then increase slowly. Probing phase will be ended by the first penalty
     *
     * @param message the sensor event coming in
     */
    private void handleSensorEvent(SensorEvent message) {
        double gyrz = gyrozHistory.shift(message.getG()[2]);
        if (lastGyrozValues.size() == maxNbLastGyrozValues) {
            lastGyrozValues.remove(0);
        }
        lastGyrozValues.add(gyrz);
        show((int) gyrz);

        if (iAmStillStanding()) {
            increase(1);
            kobayashi.tell(new PowerAction((int) currentPower), getSelf());
            return;
        }

        if (!weDiscoveredTheMap && currentPower < INITIAL_POWER) {
            increase(1);
        }

        if (weDiscoveredTheMap && probing && message.getTimeStamp() > lastIncrease + 20000) {
            lastIncrease = message.getTimeStamp();
            increase(10);
        }

        String s = triWay();

        if(!s.isEmpty()) {
            history = history + s;
            if (s.equals("S")) {
                System.out.println("S_cnt: " + S_cnt);
                S_cnts.add(S_cnt);
                S_cnt = 0;
            }
            System.out.println(history);
            System.out.println("Entering " + s + " curve");

            if (weDiscoveredTheMap)
                myPositionOnTheTrackIs = (myPositionOnTheTrackIs + 1) % map.size();

            if (!weDiscoveredTheMap) {
                track = track + s;
                String mapAsString = TrackPattern.recognize(track);
                if (!mapAsString.isEmpty()) {
                    weDiscoveredTheMap = true;
                    System.out.println("FOUND: " + mapAsString);
                    for (Character sectionAsChar : mapAsString.toCharArray()) {
                        Section section = new Section();
                        section.type = sectionAsChar;
                        section.preferredPower = INITIAL_POWER;
                        map.add(section);
                    }
                    is_lost = false;
                    track = mapAsString;

                    int j = S_cnts.size() - 1;
                    for (int i = map.size() - 1; i >= 0; i--) {
                        Section section = map.get(i);
                        if (section.type != 'S')
                            continue;
                        section.S_cnt = S_cnts.get(j);
                        if (section.S_cnt > LONG_STRAIGHT_THRESHOLD) {
                            section.preferredPower = LONG_STRAIGHT_PREFERRED_POWER;
                        }
                        j--;
                    }

                    myPositionOnTheTrackIs = 0;
                }
            }


            if (weDiscoveredTheMap) {
                int pos = myPositionOnTheTrackIs - 1;
                if (pos < 0)
                    pos = map.size() - 1;
                System.out.println("Pilot going at " + map.get(pos).preferredPower);
                kobayashi.tell(new PowerAction(map.get(pos).preferredPower), getSelf());
            } else {
                kobayashi.tell(new PowerAction((int) currentPower), getSelf());
            }


            if (weDiscoveredTheMap && !is_lost) {
                if (!s.isEmpty()) {
                    if (track.charAt(myPositionOnTheTrackIs) != s.charAt(0)) {
                        is_lost = true;
                    } else {
                        myPositionOnTheTrackIs = (myPositionOnTheTrackIs + 1) % track.length();
                    }
                }
            } else if (weDiscoveredTheMap && is_lost) {
                if (!s.isEmpty()) {
                    track_since_lost = track_since_lost + s;
                    int i = findIndex();
                    System.out.println("Track since lost : " + track_since_lost);
                    System.out.println("Track : " + track);
                    if (i > -1) {
                        System.out.println("Found at index : " + (i % track.length()));
                        is_lost = false;
                        myPositionOnTheTrackIs = (i + track_since_lost.length()) % track.length();
                        track_since_lost = "";
                        System.out.println("Got back in track !!");
                    }
                }
            }
            kobayashi.tell(new PowerAction((int) currentPower), getSelf());
        }
    }

    private int findIndex() {
        boolean unique = true;
        String t = track + track;
        int r = -1;
        int n = track_since_lost.length();
        for(int i = 0; i <= track.length(); i++) {
            if(t.substring(i, i+n).equals(track_since_lost)) {
                if(r == -1) {
                    r = i;
                } else {
                    unique = false;
                }
            }
        }

        if(r == -1) {
            track_since_lost = "";
        } else if(!unique) {
            return -1;
        }
        return r;
    }

    private String triWay() {
        if (isLeftCurve(lastGyrozValues)) {
            currentSection = SECTION_E.LEFT_CURVE;
            return "L";
        } else if (isRightCurve(lastGyrozValues)) {
            currentSection = SECTION_E.RIGHT_CURVE;
            return "R";
        } else if (isStraight(lastGyrozValues)) {
            currentSection = SECTION_E.STRAIGHT;
            return "S";
        }
        return "";
    }

    private int increase(double val) {
        currentPower += val;
        return (int) currentPower;
    }

    private boolean iAmStillStanding() {
        return gyrozHistory.currentStDev() < 3;
    }

    private void show(int gyr2) {
        int scale = 120 * (gyr2 - (-10000)) / 20000;
        System.out.println(StringUtils.repeat(" ", scale) + gyr2);
    }


}
