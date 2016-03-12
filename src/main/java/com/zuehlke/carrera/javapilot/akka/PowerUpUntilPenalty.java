package com.zuehlke.carrera.javapilot.akka;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import com.zuehlke.carrera.relayapi.messages.PenaltyMessage;
import com.zuehlke.carrera.relayapi.messages.RaceStartMessage;
import com.zuehlke.carrera.relayapi.messages.SensorEvent;
import com.zuehlke.carrera.timeseries.FloatingHistory;
import org.apache.commons.lang.StringUtils;

import java.lang.reflect.Array;
import java.util.ArrayList;

/**
 * this logic node increases the power level by 10 units per 0.5 second until it receives a penalty
 * then reduces by ten units.
 */
public class PowerUpUntilPenalty extends UntypedActor {

    private final ActorRef kobayashi;

    private double currentPower = 0;

    private SECTION_E currentSection = SECTION_E.STILL_STANDING;

    private final int MAX_POWER = 255;

    private final int INITIAL_POWER = 100;

    private String track = "";

    private String lap = "";

    private ArrayList<Section> map = new ArrayList<>();

    private boolean newSection = false;

    private int currentSectionIndex = 0;

    private boolean probing = true;

    private long lastIncrease = 0;

    private boolean weDiscoveredTheMap = false;

    private final int NbGyrozValues = 10;

    private FloatingHistory gyrozHistory = new FloatingHistory(8);

    private final int STANDBY_THRESH = 5;

    private ArrayList<Double> lastGyrozValues = new ArrayList<>();

    private double safeSpeed = INITIAL_POWER;

    private boolean isLost = false;

    private String lostTrack = "";

    public class Section {
        String direction;
        double entry_power;
        double leaving_power;
        long length;

        public String toString() {
            return direction + ", " + entry_power +  ", " + leaving_power + ", " + length;
        }
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
        lap = "";
        isLost=false;
        lostTrack="";
    }

    private void handlePenaltyMessage() {
        if(currentPower < safeSpeed) {
            currentPower -= 10;
            safeSpeed = currentPower;
        }
        System.out.println("PENALTY");
        kobayashi.tell(new PowerAction((int) currentPower), getSelf());
        probing = false;
    }

    private boolean isLeftCurve() {
        if (lastGyrozValues.size() < NbGyrozValues)
            return false;

        if (currentSection == SECTION_E.LEFT_CURVE || currentSection == SECTION_E.RIGHT_CURVE)
            return false;

        for (Double f : lastGyrozValues) {
            if (f >= -500)
                return false;
        }

        return true;
    }

    private boolean isRightCurve() {
        if (lastGyrozValues.size() < NbGyrozValues)
            return false;

        if (currentSection == SECTION_E.RIGHT_CURVE || currentSection == SECTION_E.LEFT_CURVE)
            return false;

        for (Double f : lastGyrozValues) {
            if (f <= 500)
                return false;
        }

        return true;
    }

    private boolean isStraight() {
        if (lastGyrozValues.size() < NbGyrozValues)
            return false;

        if (currentSection == SECTION_E.STRAIGHT) {
            return false;
        }

        for (Double f : lastGyrozValues) {
            if (f < -500 || f > 500)
                return false;
        }

        return true;
    }



    enum PHASE_E {
        DISCOVERY,
        SAFESPEED,
        LOST,
        OPTIMIZE
    }

    private PHASE_E prevPhase;
    private PHASE_E phase = PHASE_E.DISCOVERY;

    /**
     * Strategy: increase quickly when standing still to overcome haptic friction
     * then increase slowly. Probing phase will be ended by the first penalty
     *
     * @param message the sensor event coming in
     */
    private void handleSensorEvent(SensorEvent message) {
        double gyrz = gyrozHistory.shift(message.getG()[2]);
        if (lastGyrozValues.size() == NbGyrozValues) {
            lastGyrozValues.remove(0);
        }
        lastGyrozValues.add(gyrz);
        //show((int) gyrz);

        switch(phase) {
            case DISCOVERY:
                discover(message);
                break;
            case SAFESPEED:
                safespeed(message);
                break;
            case LOST:
                lostRecovery(message);
                break;
            case OPTIMIZE:
                optimize();
                break;
        }
        kobayashi.tell(new PowerAction((int) currentPower), getSelf());
    }

    boolean discov_first = true;
    ArrayList<Long> discov_times = new ArrayList<>();
    private void discover(SensorEvent message) {
        if(isStandingStill() || currentPower < INITIAL_POWER) {
            increase(1);
        }

        String directionChange = getDirChange();
        if(!directionChange.isEmpty() && !discov_first) {
            discov_times.add(message.getTimeStamp());
            track = track + directionChange;
            System.out.println(discov_times);
            addMap(directionChange, 0, currentPower);
            System.out.println(map);
            System.out.println(track);
            System.out.println(directionChange);
            lap = TrackPattern.recognize(track);
            if (!lap.isEmpty()) {

                phase = PHASE_E.SAFESPEED;
                addDelays(map, discov_times);
                System.out.println(map);
                System.out.println(lap);
                currentSectionIndex = 0;
            }
        }else if(!directionChange.isEmpty()){
            discov_first = false;
        }

    }

    private String strSec(SECTION_E s) {
        switch(s) {
            case LEFT_CURVE:
                return "L";
            case RIGHT_CURVE:
                return "R";
            case STRAIGHT:
                return "S";
            case STILL_STANDING:
                return "";
        }
        return "";
    }

    private void addMap(String dir, long delay, double power) {
        Section s = new Section();
        s.direction = dir;
        s.entry_power = power;
        s.leaving_power = power;
        s.length = delay;
        map.add(s);
    }

    private void addDelays(ArrayList<Section> m, ArrayList<Long> l) {

        int n = m.size();
        for (int i = 0 ; i < n/2 ; i++){
            m.remove(n-i - 1);
            m.get(i).length = l.get(i+1) - l.get(i);
        }

    }


    private void safespeed(SensorEvent message) {

        String currentStringDirection = getDirChange();
        if (!currentStringDirection.isEmpty()) {
            if(lap.charAt(currentSectionIndex) != currentStringDirection.charAt(0)) {
                System.out.println("Got lost ! " + lap.charAt(currentSectionIndex) + " vs " + currentStringDirection.charAt(0));
                prevPhase = PHASE_E.SAFESPEED;
                lostRecovery(currentStringDirection);
            } else {
                if (probing) {
                    if (isStandingStill()) {
                        increase(1);
                    } else if (message.getTimeStamp() > lastIncrease + duration) {
                        lastIncrease = message.getTimeStamp();
                        increase(3);
                    }
                } else {
                    phase = PHASE_E.OPTIMIZE;
                }
            }
            safeSpeed = Double.max(safeSpeed, currentPower);
            currentSectionIndex = (currentSectionIndex+1)%lap.length();
        }
    }
    private void lostRecovery(String direction) {
        lostTrack = lostTrack + direction;
        phase = PHASE_E.LOST;
        int i = findIndex();
        if(i > -1) {
            currentSectionIndex = (i+lostTrack.length()) % lap.length();
            lostTrack = "";
            phase = prevPhase;
        }
    }

    private void lostRecovery(SensorEvent message){
        String dir = getDirChange();
        if(!dir.isEmpty()){
            lostRecovery(dir);
        }
    }

    private int findIndex() {
        int k = -2;
        int n = lostTrack.length();
        String lap2 = lap+lap;
        for(int i = 0; i < lap.length(); i++) {
            if(lap2.substring(i, i+n).equals(lostTrack)) {
                if (k == -2) {
                    k = i;
                } else {
                    k = -1;
                }
            }
        }
        if(k == -2) {
            lostTrack = "";
        }
        return k;
    }

    private void optimize() {
        String dir = getDirChange();
        if(!dir.isEmpty()) {
            
        }

    }

    private String getDirChange() {
        if(isLeftCurve()) {
            currentSection = SECTION_E.LEFT_CURVE;
            return "L";
        }
        if(isRightCurve()){
            currentSection = SECTION_E.RIGHT_CURVE;
            return "R";
        }
        if(isStraight()){
            currentSection = SECTION_E.STRAIGHT;
            return "S";
        }
        return "";
    }

    private int increase(double val) {
        currentPower = Math.min(currentPower + val, MAX_POWER);
        return (int) currentPower;
    }

    private boolean isStandingStill() {
        boolean t = gyrozHistory.currentStDev() < STANDBY_THRESH;
        return t;
    }

    private void show(int gyr2) {
        int scale = 120 * (gyr2 - (-10000)) / 20000;
        System.out.println(StringUtils.repeat(" ", scale) + gyr2);
    }


}
