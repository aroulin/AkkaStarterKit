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

    // VERY IMPORTANT: TWEAK THEM FOR REAL TRACK OR SIMULATOR
    private final int INITIAL_POWER = 100;

    private final int MAX_POWER = 160;

    private final int LONG_STRAIGHT_THRESHOLD = 200;

    private final int LONG_STRAIGHT_PREFERRED_POWER = 140;

    private int maxNbLastGyrozValues = 10;

    private SECTION_E currentSection = SECTION_E.STILL_STANDING;

    private String track = "";

    private boolean newSection = false;

    private boolean probing = true;

    private long lastIncrease = 0;

    private boolean weDiscoveredTheMap = false;

    private FloatingHistory gyrozHistory = new FloatingHistory(8);

    private ArrayList<Double> lastGyrozValues = new ArrayList<>();

    private int myPositionOnTheTrackIs = 0;



    enum SECTION_E {
        STILL_STANDING,
        STRAIGHT,
        LEFT_CURVE,
        RIGHT_CURVE
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

    private int S_cnt = 0;

    private boolean isStraight(ArrayList<Double> lastValues) {
        if (lastValues.size() < maxNbLastGyrozValues)
            return false;

        if (currentSection == SECTION_E.STRAIGHT) {
            S_cnt++;
            return false;
        }

        for (Double f : lastValues) {
            if (f < -500 || f > 500)
                return false;
        }

        return true;
    }

    private ArrayList<Section> map = new ArrayList<Section>();
    private ArrayList<Integer> S_cnts = new ArrayList<Integer>();

    public class Section {
        Character type; // L, R or S
        int preferredPower = INITIAL_POWER;
        int S_cnt = 0;
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

        if (isLeftCurve(lastGyrozValues)) {
            currentSection = SECTION_E.LEFT_CURVE;
            track = track + 'L';
            System.out.println("S_cnt: " + S_cnt);
            S_cnts.add(S_cnt);
            S_cnt = 0;
            System.out.println(track);
            System.out.println("Entering Left Curve");
            if (weDiscoveredTheMap)
                myPositionOnTheTrackIs = (myPositionOnTheTrackIs + 1) % map.size();
        } else if (isRightCurve(lastGyrozValues)) {
            currentSection = SECTION_E.RIGHT_CURVE;
            track = track + 'R';
            System.out.println("S_cnt: " + S_cnt);
            S_cnts.add(S_cnt);
            S_cnt = 0;
            System.out.println(track);
            System.out.println("Entering Right curve");
            if (weDiscoveredTheMap)
                myPositionOnTheTrackIs = (myPositionOnTheTrackIs + 1) % map.size();
        } else if (isStraight(lastGyrozValues)) {
            currentSection = SECTION_E.STRAIGHT;
            track = track + 'S';
            System.out.println(track);
            System.out.println("Entering straight section");
            if (weDiscoveredTheMap)
                myPositionOnTheTrackIs = (myPositionOnTheTrackIs + 1) % map.size();
        }

        if (!weDiscoveredTheMap && !TrackPattern.recognize(track).isEmpty()) {
            weDiscoveredTheMap = true;
            String mapAsString = TrackPattern.recognize(track);
            System.out.println("FOUND: " + mapAsString);

            for (Character sectionAsChar: mapAsString.toCharArray()) {
                Section section = new Section();
                section.type = sectionAsChar;
                section.preferredPower = INITIAL_POWER;
                map.add(section);
            }

            // Traverse the S sections backwards, yeah bitch!
            int j = S_cnts.size()-1;
            for(int i = map.size()-1; i >= 0; i--) {
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

        if (weDiscoveredTheMap) {
            int pos = myPositionOnTheTrackIs - 1;
            if (pos < 0)
                pos = map.size() - 1;
            System.out.println("Pilot going at " + map.get(pos).preferredPower );
            kobayashi.tell(new PowerAction(map.get(pos).preferredPower), getSelf());
        } else {
            kobayashi.tell(new PowerAction((int) currentPower), getSelf());
        }
    }

    private int increase(double val) {
        currentPower = Math.min(currentPower + val, MAX_POWER);
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
