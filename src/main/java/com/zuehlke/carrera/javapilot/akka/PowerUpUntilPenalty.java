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
 *  this logic node increases the power level by 10 units per 0.5 second until it receives a penalty
 *  then reduces by ten units.
 */
public class PowerUpUntilPenalty extends UntypedActor {

    private final ActorRef kobayashi;

    private double currentPower = 0;

    private long lastIncrease = 0;

    private final int constantPower = 110;

    private SECTION currentSection = SECTION.STILL_STANDING;

    private ArrayList<SECTION> track = new ArrayList<SECTION>(), compareTrack = new ArrayList<SECTION>();
    private ArrayList<SECTION> checkTrack = new ArrayList<SECTION>();

    private int indexCompareTrack = 0;

    private boolean newSection = false;

    private boolean trackFound = false;

    private FloatingHistory gyrozHistory = new FloatingHistory(8);

    private ArrayList<Double> lastValues = new ArrayList<Double>();

    enum SECTION {
        STILL_STANDING,
        STRAIGHT,
        LEFT_CURVE,
        RIGHT_CURVE
    }

    /**
     * @param pilotActor The central pilot actor
     * @param duration the period between two increases
     * @return the actor props
     */
    public static Props props( ActorRef pilotActor, int duration ) {
        return Props.create(
                PowerUpUntilPenalty.class, () -> new PowerUpUntilPenalty(pilotActor, duration ));
    }
    private final int duration;

    public PowerUpUntilPenalty(ActorRef pilotActor, int duration) {
        lastIncrease = System.currentTimeMillis();
        this.kobayashi = pilotActor;
        this.duration = duration;
    }


    @Override
    public void onReceive(Object message) throws Exception {

        if ( message instanceof SensorEvent ) {
            handleSensorEvent((SensorEvent) message);

        } else if ( message instanceof PenaltyMessage) {
            handlePenaltyMessage ();

        } else if ( message instanceof RaceStartMessage) {
            handleRaceStart();

        } else {
            unhandled(message);
        }
    }

    private void handleRaceStart() {
        currentPower = 0;
        lastIncrease = 0;
        gyrozHistory = new FloatingHistory(8);
        currentSection = SECTION.STILL_STANDING;
        indexCompareTrack = 0;
        newSection = false;
        trackFound = false;
        lastValues.clear();
    }

    private void handlePenaltyMessage() {
        currentPower -= 10;
        kobayashi.tell(new PowerAction((int)currentPower), getSelf());
    }

    private int nbLastValues = 4;

    private boolean isLeftCurve(ArrayList<Double> lastValues) {
        if (lastValues.size() < nbLastValues)
            return false;

        for (Double f: lastValues) {
            if (f >= -500) {
                return false;
            }
        }

        return true;
    }

    private boolean isRightCurve(ArrayList<Double> lastValues) {
        if (lastValues.size() < nbLastValues)
            return false;

        for (Double f: lastValues) {
            if (f <= 500) {
                return false;
            }
        }

        return true;
    }

    private boolean isStraight(ArrayList<Double> lastValues) {
        if (lastValues.size() < nbLastValues)
            return false;

        for (Double f: lastValues) {
            if (f < -500 || f > 500) {
                return false;
            }
        }

        return true;
    }


    /**
     * Strategy: increase quickly when standing still to overcome haptic friction
     * then increase slowly. Probing phase will be ended by the first penalty
     * @param message the sensor event coming in
     */
    private void handleSensorEvent(SensorEvent message) {

        double gyrz = gyrozHistory.shift(message.getG()[2]);
        if (lastValues.size() == nbLastValues) {
            lastValues.remove(0);
        }
        lastValues.add(gyrz);

        show ((int)gyrz);

        if (iAmStillStanding()) {
            increase(0.5);
        } else {
            if (isLeftCurve(lastValues) && currentSection != SECTION.LEFT_CURVE && currentSection != SECTION.RIGHT_CURVE) {
                currentSection = SECTION.LEFT_CURVE;
                newSection = true;
                System.out.println("Entering Left Curve");
            } else if (isRightCurve(lastValues) && currentSection != SECTION.RIGHT_CURVE && currentSection != SECTION.LEFT_CURVE) {
                currentSection = SECTION.RIGHT_CURVE;
                newSection = true;
                System.out.println("Entering Right curve");
            } else if (isStraight(lastValues) && currentSection != SECTION.STRAIGHT){
                currentSection = SECTION.STRAIGHT;
                newSection = true;
                System.out.println("Entering straight section");
            }

            if (newSection && !trackFound) {

                if (track.size() < 9) {
                    track.add(currentSection);
                } else {
                    do {
                        if (track.get(compareTrack.size()) == currentSection) {
                            compareTrack.add(currentSection);
                            break;
                        } else if (compareTrack.isEmpty()) {
                            track.add(currentSection);
                            break;
                        } else {
                            do  {
                                track.add(compareTrack.remove(0));
                            } while (!compareTrack.isEmpty() && !track.subList(0, compareTrack.size()).equals(compareTrack));
                        }
                    } while (true);
                }
                newSection = false;

                if (track.equals(compareTrack)) {
                    trackFound = true;
                    System.out.println(compareTrack.toString());
                }
            /*} else if (newSection && trackFound){
                checkTrack.add(currentSection);
                if (!track.subList(0, checkTrack.size()).equals(checkTrack)) {

                }
                }*/
            }
        }

        kobayashi.tell(new PowerAction((int)currentPower), getSelf());
    }

    private int increase ( double val ) {
        currentPower += val;
        return (int)currentPower;
    }

    private boolean iAmStillStanding() {
        return gyrozHistory.currentStDev() < 3;
    }

    private void show(int gyr2) {
        int scale = 120 * (gyr2 - (-10000) ) / 20000;
        System.out.println(StringUtils.repeat(" ", scale) + gyr2);
    }


}
