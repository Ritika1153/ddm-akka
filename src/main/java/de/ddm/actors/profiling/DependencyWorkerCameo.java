package de.ddm.actors.profiling;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import de.ddm.serialization.AkkaSerializable;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class DependencyWorkerCameo extends AbstractBehavior<DependencyWorkerCameo.Message> {

	////////////////////
	// Actor Messages //
	////////////////////

	public interface Message extends AkkaSerializable {
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ResultMessage implements Message {
		private static final long serialVersionUID = -7642425159675583598L;
		int[] lhss;
		int[] rhss;
	}

	////////////////////////
	// Actor Construction //
	////////////////////////

	public static final String DEFAULT_NAME = "dependencyWorkerCameo";

	public static Behavior<Message> create(ActorRef<DependencyMiner.Message> dependencyMiner, int numHelpers, int workerId) {
		return Behaviors.setup(context -> new DependencyWorkerCameo(context, dependencyMiner, numHelpers, workerId));
	}

	private DependencyWorkerCameo(ActorContext<Message> context, ActorRef<DependencyMiner.Message> dependencyMiner, int numHelpers, int workerId) {
		super(context);
		this.dependencyMiner = dependencyMiner;
		this.numHelpers = numHelpers;
		this.workerId = workerId;
		this.lhss = new IntArrayList();
		this.rhss = new IntArrayList();
		this.messagesReceived = 0;
	}

	/////////////////
	// Actor State //
	/////////////////

	private final ActorRef<DependencyMiner.Message> dependencyMiner;
	private final int numHelpers;
	private final int workerId;
	private final IntList lhss;
	private final IntList rhss;
	private int messagesReceived;

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive<Message> createReceive() {
		return newReceiveBuilder()
				.onMessage(ResultMessage.class, this::handle)
				.build();
	}

	private Behavior<Message> handle(ResultMessage message) {
		for (int lhs : message.getLhss())
			this.lhss.add(lhs);
		for (int rhs : message.getRhss())
			this.rhss.add(rhs);

		this.messagesReceived++;

		if (this.messagesReceived >= this.numHelpers) {
			this.dependencyMiner.tell(new DependencyMiner.ResultMessage(
					this.lhss.toIntArray(),
					this.rhss.toIntArray(),
					this.workerId
			));
			return Behaviors.stopped();
		}

		return this;
	}
}
