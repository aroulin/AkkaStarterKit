package com.zuehlke.carrera.javapilot.akka;

import akka.actor.*;
import akka.japi.Creator;
import com.zuehlke.carrera.javapilot.io.*;
import com.zuehlke.carrera.relayapi.messages.*;
import scala.concurrent.duration.Duration;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class RaceRecorderActor extends UntypedActor {

    public static final String DATA_DIRECTORY = "data";
    public static final int FREQUENCY = 1;

    private final RaceRecorderPlayer recorder = new RaceRecorderPlayer(DATA_DIRECTORY);
    private final ActorRef kobayashi;
    private boolean replaying = false;
    private Supplier<Object> supplier;
    private Cancellable schedule;

    public RaceRecorderActor(ActorRef kobayashi) {
        this.kobayashi = kobayashi;
    }

    public static Props props ( ActorRef kobayashi ) {
        return Props.create(new Creator<RaceRecorderActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public RaceRecorderActor create() throws Exception {
                return new RaceRecorderActor(kobayashi);
            }
        });
    }

    @Override
    public void onReceive(Object message) throws Exception {

        if ( replaying ) {
            if (message instanceof NextEventCommand) {
                handleNextMessage();
            } else if ( message instanceof StopReplayCommand) {
                stopReplaying();
            }
            return;
        }

        if ( message instanceof RaceStartMessage ) {
            recorder.record((RaceStartMessage) message);
        } else if ( message instanceof RaceStopMessage ) {
            recorder.close();
        } else if ( message instanceof SensorEvent) {
            recorder.record((SensorEvent) message );
        } else if ( message instanceof VelocityMessage) {
            recorder.record((VelocityMessage) message );
        } else if ( message instanceof PenaltyMessage) {
            recorder.record((PenaltyMessage) message, System.currentTimeMillis() );
        } else if ( message instanceof PowerControl) {
            recorder.record((PowerControl) message);
        } else if ( message instanceof StartReplayCommand) {
            handleReplay(((StartReplayCommand)message).getTag());
        } else {
            unhandled(message);
        }
    }

    private void stopReplaying() {
        recorder.close();
        replaying = false;
        schedule.cancel();
        getSelf().tell (PoisonPill.getInstance(), getSelf());
    }

    private void handleNextMessage() {
        try {
            Object nextMessage = supplier.get();
            if ( nextMessage instanceof PowerControl) {
                return;
            }
            kobayashi.tell(nextMessage, getSelf());
        } catch (EndOfStreamException eose ) {
            kobayashi.tell(new StopReplayCommand(), getSelf());
            stopReplaying();
        }
    }

    private void handleReplay(String tag) {
        replaying = true;
        try {
            supplier = recorder.getSupplier(tag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        schedulePlay(tag, FREQUENCY);
    }

    protected void schedulePlay( String tag, int millies ) {
        schedule =
        getContext().system().scheduler().schedule(
                Duration.create(millies, TimeUnit.MILLISECONDS),
                Duration.create(millies, TimeUnit.MILLISECONDS),
                getSelf(), new NextEventCommand(),
                getContext().system().dispatcher(), null);
    }


}