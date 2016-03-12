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

public class PowerUpUntilPenalty extends UntypedActor {

    private final ActorRef kobayashi;

    // Parameters
    private final int INITIAL_POWER = 105;

    // Current state variables
    private double currentPower = 0;
    private SECTION_E currentSection = SECTION_E.STILL_STANDING;
    private int currentSectionIndex = 0;

    // Phase variables
    private PHASE_E prevPhase;
    private PHASE_E currentPhase = PHASE_E.DISCOVERY;

    // Safe power computation variables
    private double safePower = INITIAL_POWER;
    private boolean TryingToIncreaseSafePower = true;
    private long lastIncreaseTimeToSafePower = 0;

    // Track related variable
    private String track = "";
    private String lap = "";
    private ArrayList<Section> map = new ArrayList<>();
    private String lostTrack = "";

    // GyroZ variables
    private final int NB_GYROZ_VALUES_TO_CONSIDER_FOR_SECTION = 10;
    private FloatingHistory gyrozHistory = new FloatingHistory(8);
    private ArrayList<Double> lastGyrozValuesAcquired = new ArrayList<>();

    public class Section {
        String direction;
        double entry_power;
        double leaving_power;
        long lengthInSeconds;

        public String toString() {
            return direction + ", " + entry_power + ", " + leaving_power + ", " + lengthInSeconds;
        }
    }

    enum PHASE_E {
        DISCOVERY,
        SAFESPEED,
        LOST,
        OPTIMIZE
    }

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
        lastIncreaseTimeToSafePower = System.currentTimeMillis();
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
        // Current state variables
        currentPower = 0;
        currentSection = SECTION_E.STILL_STANDING;
        currentSectionIndex = 0;

        // Safe power computation variables
        safePower = INITIAL_POWER;
        TryingToIncreaseSafePower = true;
        lastIncreaseTimeToSafePower = 0;

        // Track related variable
        track = "";
        lap = "";
        map = new ArrayList<>();
        lostTrack = "";

        // GyroZ variables
        gyrozHistory = new FloatingHistory(8);
        lastGyrozValuesAcquired = new ArrayList<>();
    }

    private void handlePenaltyMessage() {
        System.out.println("Pilot: Oh shit, I got a penalty at speed: " + currentPower);
        if (currentPower < safePower) {
            currentPower -= 10;
            safePower = currentPower;
        }
        System.out.println("Pilot: Reducing safe power and current power to " + safePower);
        kobayashi.tell(new PowerAction((int) currentPower), getSelf());
        TryingToIncreaseSafePower = false;
    }

    private boolean isLeftCurveComingNext() {
        if (lastGyrozValuesAcquired.size() < NB_GYROZ_VALUES_TO_CONSIDER_FOR_SECTION)
            return false; // We don't know yet

        if (currentSection == SECTION_E.LEFT_CURVE || currentSection == SECTION_E.RIGHT_CURVE)
            return false;

        for (Double f : lastGyrozValuesAcquired) {
            if (f >= -500)
                return false;
        }

        return true;
    }

    private boolean isRightCurveComingNext() {
        if (lastGyrozValuesAcquired.size() < NB_GYROZ_VALUES_TO_CONSIDER_FOR_SECTION)
            return false; // We don't know yet

        if (currentSection == SECTION_E.RIGHT_CURVE || currentSection == SECTION_E.LEFT_CURVE)
            return false;

        for (Double f : lastGyrozValuesAcquired) {
            if (f <= 500)
                return false;
        }

        return true;
    }

    private boolean isStraightComingNext() {
        if (lastGyrozValuesAcquired.size() < NB_GYROZ_VALUES_TO_CONSIDER_FOR_SECTION)
            return false; // We don't know yet

        if (currentSection == SECTION_E.STRAIGHT) {
            return false;
        }

        for (Double f : lastGyrozValuesAcquired) {
            if (f < -500 || f > 500)
                return false;
        }

        return true;
    }

    /**
     * Strategy: increase quickly when standing still to overcome haptic friction
     * then increase slowly. Probing currentPhase will be ended by the first penalty
     *
     * @param message the sensor event coming in
     */
    private void handleSensorEvent(SensorEvent message) {

        // Add new gyroZ values to the last values to consider to determine the next section
        double gyrz = gyrozHistory.shift(message.getG()[2]);
        if (lastGyrozValuesAcquired.size() == NB_GYROZ_VALUES_TO_CONSIDER_FOR_SECTION) {
            lastGyrozValuesAcquired.remove(0);
        }
        lastGyrozValuesAcquired.add(gyrz);

        // Do we want to show gyro values ?
        //show((int) gyrz);

        switch (currentPhase) {
            case DISCOVERY:
                discover(message);
                break;
            case SAFESPEED:
                safePower(message);
                break;
            case LOST:
                lostRecovery(message);
                break;
            case OPTIMIZE:
                optimize(message);
                break;
        }
        kobayashi.tell(new PowerAction((int) currentPower), getSelf());
    }

    boolean discovSkipFirstSection = true;
    ArrayList<Long> discov_times = new ArrayList<>();

    private void discover(SensorEvent message) {
        if (isStandingStill() || currentPower < INITIAL_POWER) {
            increase(1);
        }

        String directionChange = getDirChange();
        if (!directionChange.isEmpty() && !discovSkipFirstSection) {
            discov_times.add(message.getTimeStamp());
            track = track + directionChange;
            System.out.println(discov_times);
            addMap(directionChange, 0, currentPower);
            System.out.println(map);
            System.out.println(track);
            System.out.println(directionChange);
            lap = TrackPattern.recognize(track);
            if (!lap.isEmpty()) {

                currentPhase = PHASE_E.SAFESPEED;
                addDelays(map, discov_times);
                System.out.println(map);
                System.out.println(lap);
                currentSectionIndex = 0;
            }
        } else if (!directionChange.isEmpty()) {
            discovSkipFirstSection = false;
        }

    }

    private void addMap(String dir, long delay, double power) {
        Section s = new Section();
        s.direction = dir;
        s.entry_power = power;
        s.leaving_power = power;
        s.lengthInSeconds = delay;
        map.add(s);
    }

    private void addDelays(ArrayList<Section> m, ArrayList<Long> l) {

        int n = m.size();
        for (int i = 0; i < n / 2; i++) {
            m.remove(n - i - 1);
            m.get(i).lengthInSeconds = l.get(i + 1) - l.get(i);
        }
    }


    private void safePower(SensorEvent message) {

        String currentStringDirection = getDirChange();

        if (!currentStringDirection.isEmpty()) { // If we change direction
            if (lap.charAt(currentSectionIndex) != currentStringDirection.charAt(0)) {
                System.out.println("Got lost ! " + lap.charAt(currentSectionIndex) + " vs " + currentStringDirection.charAt(0));
                prevPhase = PHASE_E.SAFESPEED;
                lostRecovery(currentStringDirection);
            } else {
                if (TryingToIncreaseSafePower) {
                    if (isStandingStill()) {
                        increase(1);
                    } else if (message.getTimeStamp() > lastIncreaseTimeToSafePower + duration) {
                        lastIncreaseTimeToSafePower = message.getTimeStamp();
                        increase(3);
                    }
                } else {
                    currentPhase = PHASE_E.OPTIMIZE;
                }
            }
            safePower = Double.max(safePower, currentPower);
            currentSectionIndex = (currentSectionIndex + 1) % lap.length();
        }
    }

    private void lostRecovery(String direction) {
        lostTrack = lostTrack + direction;
        currentPhase = PHASE_E.LOST;
        int i = findIndex();
        if (i > -1) {
            currentSectionIndex = (i + lostTrack.length()) % lap.length();
            lostTrack = "";
            currentPhase = prevPhase;
        }
    }

    private void lostRecovery(SensorEvent message) {
        String dir = getDirChange();
        if (!dir.isEmpty()) {
            lostRecovery(dir);
        }
    }

    private int findIndex() {
        int k = -2;
        int n = lostTrack.length();
        String lap2 = lap + lap;
        for (int i = 0; i < lap.length(); i++) {
            if (lap2.substring(i, i + n).equals(lostTrack)) {
                if (k == -2) {
                    k = i;
                } else {
                    k = -1;
                }
            }
        }
        if (k == -2) {
            lostTrack = "";
        }
        return k;
    }

    long wait_timestamp = 0;
    final int BIG_STRAIGHT_LENGTH_THRESHOLD = 1500;
    //final long BIG_STRAIGHT_TIMESTAMP_WAIT = 100;

    long optimizeBeginTimestamp;
    int next_power_value = 0;

    private void optimize(SensorEvent message) {
        String dir = getDirChange();

        if (!dir.isEmpty()) {

            optimizeBeginTimestamp = 0;

            Section s = map.get(currentSectionIndex);
            currentSectionIndex = (currentSectionIndex + 1) % lap.length();

            if (s.direction.equals("S")) {
                wait_timestamp = (s.lengthInSeconds * 25) / 100;
                currentPower = 170;
                next_power_value = 50;
                optimizeBeginTimestamp = message.getTimeStamp();
            } else if (s.direction.equals("L") || s.direction.equals("R")) {
                wait_timestamp = (s.lengthInSeconds * 45) / 100;
                next_power_value = (int) safePower;
            }

        } else {
            long optimizeTimestampCurrent = message.getTimeStamp();
            if (optimizeTimestampCurrent - optimizeBeginTimestamp > wait_timestamp) {
                optimizeBeginTimestamp = 0;
                wait_timestamp = 0;

                currentPower = next_power_value;
            }
        }
    }

    private String getDirChange() {
        if (isLeftCurveComingNext()) {
            currentSection = SECTION_E.LEFT_CURVE;
            return "L";
        }
        if (isRightCurveComingNext()) {
            currentSection = SECTION_E.RIGHT_CURVE;
            return "R";
        }
        if (isStraightComingNext()) {
            currentSection = SECTION_E.STRAIGHT;
            return "S";
        }
        return "";
    }

    private int increase(double val) {
        final int MAX_POWER = 200;
        currentPower = Math.min(currentPower + val, MAX_POWER);
        return (int) currentPower;
    }

    private boolean isStandingStill() {
        int STANDBY_THRESH = 5;
        return gyrozHistory.currentStDev() < STANDBY_THRESH;
    }

    private void show(int gyr2) {
        int scale = 120 * (gyr2 - (-10000)) / 20000;
        System.out.println(StringUtils.repeat(" ", scale) + gyr2);
    }


}
