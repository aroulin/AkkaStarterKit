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

    private final int INITIAL_POWER = 110;

    private SECTION currentSection = SECTION.STILL_STANDING;

    private String track = "";

    // For getting our way after a stop
    private int current_position_index = 0;
    private String track_since_lost = "";
    private boolean is_lost = true;

    private boolean newSection = false;

    private String history = "";

    private boolean probing = true;

    private long lastIncrease = 0;

    private boolean trackFound = false;

    private FloatingHistory gyrozHistory = new FloatingHistory(8);

    private ArrayList<Double> lastGyrozValues = new ArrayList<>();

    enum SECTION {
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
        currentSection = SECTION.STILL_STANDING;
        newSection = false;
        trackFound = false;
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

        track_since_lost = "";

        // Got stuck
        is_lost = true;



    }

    private int maxNbLastGyrozValues = 4;

    private boolean isLeftCurve(ArrayList<Double> lastValues) {
        if (lastValues.size() < maxNbLastGyrozValues)
            return false;

        if (currentSection == SECTION.LEFT_CURVE || currentSection == SECTION.RIGHT_CURVE)
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

        if (currentSection == SECTION.RIGHT_CURVE || currentSection == SECTION.LEFT_CURVE)
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

        if (currentSection == SECTION.STRAIGHT)
            return false;

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
        //show((int) gyrz);

        if (iAmStillStanding()) {
            increase(2);
            kobayashi.tell(new PowerAction((int) currentPower), getSelf());
            return;
        }

        if (!trackFound && currentPower < INITIAL_POWER) {
            increase(1);
        }

        if (trackFound && probing && message.getTimeStamp() > lastIncrease + duration) {
            lastIncrease = message.getTimeStamp();
            increase(3);
        }


        if(!trackFound) {
            track = track + triWay();
            if (!TrackPattern.recognize(track).isEmpty()) {
                trackFound = true;
                System.out.println("FOUND: " + TrackPattern.recognize(track));
                is_lost = false;
                track = TrackPattern.recognize(track);
                current_position_index = 0;
            }
        } else if(!is_lost){
            String s = triWay();
            if(!s.isEmpty()) {
                if(track.charAt(current_position_index) != s.charAt(0)) {
                    is_lost = true;
                } else {
                    current_position_index = (current_position_index+1)%track.length();
                }
            }
        }

        if(is_lost) {
            String s = triWay();
            if(!s.isEmpty()){
                track_since_lost = track_since_lost+s;
                int i = findIndex();
                System.out.println("Track since lost : " + track_since_lost);
                System.out.println("Track : " + track);
                if(i > -1) {
                    System.out.println("Found at index : " + (i%track.length()));
                    is_lost = false;
                    current_position_index = (i+track_since_lost.length())%track.length();
                    track_since_lost = "";
                    System.out.println("Got back in track !!");
                }
            }
        }
        history = history+triWay();
        //System.out.println("History" + history);
        kobayashi.tell(new PowerAction((int) currentPower), getSelf());
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
            currentSection = SECTION.LEFT_CURVE;
            return "L";
        } else if (isRightCurve(lastGyrozValues)) {
            currentSection = SECTION.RIGHT_CURVE;
            return "R";
        } else if (isStraight(lastGyrozValues)) {
            currentSection = SECTION.STRAIGHT;
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
