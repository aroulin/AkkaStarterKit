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

    private FloatingHistory gyrozHistory = new FloatingHistory(8);

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
    }

    private void handlePenaltyMessage() {
        currentPower -= 10;
        kobayashi.tell(new PowerAction((int)currentPower), getSelf());
    }

    /**
     * Strategy: increase quickly when standing still to overcome haptic friction
     * then increase slowly. Probing phase will be ended by the first penalty
     * @param message the sensor event coming in
     */
    private void handleSensorEvent(SensorEvent message) {

        double gyrz = gyrozHistory.shift(message.getG()[2]);
         show ((int)gyrz);

        if (iAmStillStanding()) {
            increase(0.5);
        } else {
            if (gyrz < -500 && currentSection != SECTION.LEFT_CURVE) {
                currentSection = SECTION.LEFT_CURVE;
                System.out.println("Entering Left Curve");
            } else if (gyrz > 500 && currentSection != SECTION.RIGHT_CURVE) {
                currentSection = SECTION.RIGHT_CURVE;
                System.out.println("Entering Right curve");
            } else if (gyrz <= 500 && gyrz >= -500 && currentSection != SECTION.STRAIGHT){
                currentSection = SECTION.STRAIGHT;
                System.out.println("Entering straight section");
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
