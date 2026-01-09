package de.ddm.actors.profiling;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import de.ddm.serialization.AkkaSerializable;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

public class DependencyWorkerHelper extends AbstractBehavior<DependencyWorkerHelper.Message> {

	////////////////////
	// Actor Messages //
	////////////////////

	public interface Message extends AkkaSerializable {
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ValidateLocalTaskMessage implements Message {
		private static final long serialVersionUID = -4025238529984914107L;
		IntList attributes1;
		IntList attributes2;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ValidateTaskMessage implements Message {
		private static final long serialVersionUID = -7642425159675583598L;
		int attribute;
		List<String> column;
		IntList localAttributes;
	}

	////////////////////////
	// Actor Construction //
	////////////////////////

	public static final String DEFAULT_NAME = "dependencyWorkerHelper";

	public static Behavior<Message> create(Int2ObjectMap<List<String>> sortedColumns, ActorRef<DependencyWorkerCameo.Message> cameo) {
		return Behaviors.setup(context -> new DependencyWorkerHelper(context, sortedColumns, cameo));
	}

	private DependencyWorkerHelper(ActorContext<Message> context, Int2ObjectMap<List<String>> sortedColumns, ActorRef<DependencyWorkerCameo.Message> cameo) {
		super(context);
		this.sortedColumns = sortedColumns;
		this.cameo = cameo;
	}

	/////////////////
	// Actor State //
	/////////////////

	private final Int2ObjectMap<List<String>> sortedColumns;
	private final ActorRef<DependencyWorkerCameo.Message> cameo;

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive<Message> createReceive() {
		return newReceiveBuilder()
				.onMessage(ValidateLocalTaskMessage.class, this::handle)
				.onMessage(ValidateTaskMessage.class, this::handle)
				.build();
	}

	private Behavior<Message> handle(ValidateLocalTaskMessage message) {
		IntList lhss = new IntArrayList();
		IntList rhss = new IntArrayList();

		for (int i = 0; i < message.getAttributes1().size(); i++) {
			int attribute1 = message.getAttributes1().getInt(i);
			int attribute2 = message.getAttributes2().getInt(i);

			List<String> column1 = this.sortedColumns.get(attribute1);
			List<String> column2 = this.sortedColumns.get(attribute2);

			if (isIncluded(column1, column2)) {
				lhss.add(attribute1);
				rhss.add(attribute2);
			}

			if (isIncluded(column2, column1)) {
				lhss.add(attribute2);
				rhss.add(attribute1);
			}
		}

		this.cameo.tell(new DependencyWorkerCameo.ResultMessage(lhss.toIntArray(), rhss.toIntArray()));
		return this;
	}

	private Behavior<Message> handle(ValidateTaskMessage message) {
		IntList lhss = new IntArrayList();
		IntList rhss = new IntArrayList();

		for (int localAttribute : message.getLocalAttributes()) {
			List<String> localColumn = this.sortedColumns.get(localAttribute);

			if (isIncluded(localColumn, message.getColumn())) {
				lhss.add(localAttribute);
				rhss.add(message.getAttribute());
			}

			if (isIncluded(message.getColumn(), localColumn)) {
				lhss.add(message.getAttribute());
				rhss.add(localAttribute);
			}
		}

		this.cameo.tell(new DependencyWorkerCameo.ResultMessage(lhss.toIntArray(), rhss.toIntArray()));
		return this;
	}

	private boolean isIncluded(List<String> dependent, List<String> referenced) {
		for (String value : dependent) {
			if (Collections.binarySearch(referenced, value) < 0)
				return false;
		}
		return true;
	}
}
