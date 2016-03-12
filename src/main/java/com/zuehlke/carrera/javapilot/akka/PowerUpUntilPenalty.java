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

    private SECTION currentSection = SECTION.STILL_STANDING;

    private ArrayList<SECTION> track = new ArrayList<>(), compareTrack = new ArrayList<>();

    private boolean newSection = false;

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
    }

    private void handlePenaltyMessage() {
        currentPower -= 10;
        kobayashi.tell(new PowerAction((int) currentPower), getSelf());
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
        show((int) gyrz);

        if (iAmStillStanding()) {
            increase(2);
            kobayashi.tell(new PowerAction((int) currentPower), getSelf());
            return;
        }

        if (isLeftCurve(lastGyrozValues)) {
            currentSection = SECTION.LEFT_CURVE;
            newSection = true;
            System.out.println("Entering Left Curve");
        } else if (isRightCurve(lastGyrozValues)) {
            currentSection = SECTION.RIGHT_CURVE;
            newSection = true;
            System.out.println("Entering Right curve");
        } else if (isStraight(lastGyrozValues)) {
            currentSection = SECTION.STRAIGHT;
            newSection = true;
            System.out.println("Entering straight section");
        }

        if (newSection && !trackFound) {
            newSection = false;

            if (track.size() < 10) {
                track.add(currentSection);
                kobayashi.tell(new PowerAction((int) currentPower), getSelf());
                return;
            }

            do {
                if (track.get(compareTrack.size()) == currentSection) {
                    compareTrack.add(currentSection);
                    break;
                } else if (compareTrack.isEmpty()) {
                    track.add(currentSection);
                    break;
                } else {
                    do {
                        track.add(compareTrack.remove(0));
                    }
                    while (!compareTrack.isEmpty() && !track.subList(0, compareTrack.size()).equals(compareTrack));
                }
            } while (true);

            if (track.equals(compareTrack)) {
                trackFound = true;
                System.out.println(compareTrack.toString());
            }
        }

        kobayashi.tell(new PowerAction((int) currentPower), getSelf());
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
