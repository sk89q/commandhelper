package com.laytonsmith.core.functions;

import com.laytonsmith.PureUtilities.Version;
import com.laytonsmith.abstraction.StaticLayer;
import com.laytonsmith.annotations.api;
import com.laytonsmith.annotations.core;
import com.laytonsmith.core.CHLog;
import com.laytonsmith.core.CHVersion;
import com.laytonsmith.core.LogLevel;
import com.laytonsmith.core.Optimizable;
import com.laytonsmith.core.ParseTree;
import com.laytonsmith.core.Script;
import com.laytonsmith.core.Static;
import com.laytonsmith.core.compiler.FileOptions;
import com.laytonsmith.core.constructs.CArray;
import com.laytonsmith.core.constructs.CBoolean;
import com.laytonsmith.core.constructs.CNull;
import com.laytonsmith.core.constructs.CString;
import com.laytonsmith.core.constructs.CVoid;
import com.laytonsmith.core.constructs.Construct;
import com.laytonsmith.core.constructs.IVariable;
import com.laytonsmith.core.constructs.IVariableList;
import com.laytonsmith.core.constructs.Target;
import com.laytonsmith.core.environments.CommandHelperEnvironment;
import com.laytonsmith.core.environments.Environment;
import com.laytonsmith.core.environments.GlobalEnv;
import com.laytonsmith.core.events.BoundEvent;
import com.laytonsmith.core.events.BoundEvent.ActiveEvent;
import com.laytonsmith.core.events.BoundEvent.Priority;
import com.laytonsmith.core.events.Driver;
import com.laytonsmith.core.events.Event;
import com.laytonsmith.core.events.EventList;
import com.laytonsmith.core.events.EventUtils;
import com.laytonsmith.core.exceptions.ConfigCompileException;
import com.laytonsmith.core.exceptions.ConfigRuntimeException;
import com.laytonsmith.core.exceptions.EventException;
import com.laytonsmith.core.functions.Exceptions.ExceptionType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 */
@core
public class EventBinding {

	public static String docs() {
		return "This class of functions provide methods to hook deep into the server's event architecture";
	}

	private static final AtomicInteger bindCounter = new AtomicInteger(0);

	@api(environments = CommandHelperEnvironment.class)
	public static class bind extends AbstractFunction implements Optimizable {

		@Override
		public String getName() {
			return "bind";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{Integer.MAX_VALUE};
		}

		@Override
		public String docs() {
			return "string {event_name, options, prefilter, event_obj, [custom_params], &lt;code&gt;} Binds some functionality to an event, so that"
					+ " when said event occurs, the event handler will fire. Returns the id of this event, so it can be unregistered"
					+ " later, if need be.";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.BindException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public boolean preResolveVariables() {
			return false;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}

		@Override
		public Boolean runAsync() {
			return false;
		}

		@Override
		public Construct exec(Target t, Environment env, Construct... args) throws ConfigRuntimeException {
			return CVoid.VOID;
		}

		@Override
		public Construct execs(Target t, Environment env, Script parent, ParseTree... nodes) {
			if (nodes.length < 5) {
				throw new ConfigRuntimeException("bind accepts 5 or more parameters", ExceptionType.InsufficientArgumentsException, t);
			}
			Construct name = parent.seval(nodes[0], env);
			Construct options = parent.seval(nodes[1], env);
			Construct prefilter = parent.seval(nodes[2], env);
			Construct event_obj = parent.eval(nodes[3], env);
			IVariableList custom_params = new IVariableList();
			for (int a = 0; a < nodes.length - 5; a++) {
				Construct var = parent.eval(nodes[4 + a], env);
				if (!(var instanceof IVariable)) {
					throw new ConfigRuntimeException("The custom parameters must be ivariables", ExceptionType.CastException, t);
				}
				IVariable cur = (IVariable) var;
				((IVariable) var).setIval(env.getEnv(GlobalEnv.class).GetVarList().get(cur.getName(), cur.getTarget()).ival());
				custom_params.set((IVariable) var);
			}
			Environment newEnv = env;
			try {
				newEnv = env.clone();
			} catch (Exception e) {
			}
			newEnv.getEnv(GlobalEnv.class).SetVarList(custom_params);
			ParseTree tree = nodes[nodes.length - 1];

			//Check to see if our arguments are correct
			if (!(options instanceof CNull || options instanceof CArray)) {
				throw new ConfigRuntimeException("The options must be an array or null", ExceptionType.CastException, t);
			}
			if (!(prefilter instanceof CNull || prefilter instanceof CArray)) {
				throw new ConfigRuntimeException("The prefilters must be an array or null", ExceptionType.CastException, t);
			}
			if (!(event_obj instanceof IVariable)) {
				throw new ConfigRuntimeException("The event object must be an IVariable", ExceptionType.CastException, t);
			}
			CString id;
			if (options instanceof CNull) {
				options = null;
			}
			if (prefilter instanceof CNull) {
				prefilter = null;
			}
			Event event;
			try {
				BoundEvent be = new BoundEvent(name.val(), (CArray) options, (CArray) prefilter,
						((IVariable) event_obj).getName(), newEnv, tree, t);
				EventUtils.RegisterEvent(be);
				id = new CString(be.getId(), t);
				event = EventList.getEvent(be.getEventName());
			} catch (EventException ex) {
				throw new ConfigRuntimeException(ex.getMessage(), ExceptionType.BindException, t);
			}

			//Set up our bind counter, but only if the event is supposed to be added to the counter
			if (event.addCounter()) {
				synchronized (bindCounter) {
					if (bindCounter.get() == 0) {
						env.getEnv(GlobalEnv.class).GetDaemonManager().activateThread(null);
						StaticLayer.GetConvertor().addShutdownHook(new Runnable() {

							@Override
							public void run() {
								synchronized (bindCounter) {
									bindCounter.set(0);
								}
							}
						});
					}
					bindCounter.incrementAndGet();
				}
			}
			return id;
		}

		@Override
		public boolean useSpecialExec() {
			return true;
		}

		@Override
		public Set<OptimizationOption> optimizationOptions() {
			return EnumSet.of(OptimizationOption.OPTIMIZE_DYNAMIC, OptimizationOption.CUSTOM_LINK);
		}

		@Override
		public ParseTree optimizeDynamic(Target t, List<ParseTree> children, FileOptions fileOptions) throws ConfigCompileException, ConfigRuntimeException {
			if (children.size() < 5) {
				throw new ConfigRuntimeException("bind accepts 5 or more parameters", ExceptionType.InsufficientArgumentsException, t);
			}
			if (!children.get(0).isConst()) {
				// This ability may be removed in the future, to allow for better compilation checks of event type, once objects are added.
				// We'll add this warning to gauge impact.
				CHLog.GetLogger().Log(CHLog.Tags.COMPILER, LogLevel.WARNING, "Use of dynamic bind. This may be removed in the future, please"
						+ " contact the developers to provide feedback if this affects you.", t);
			}
			return null;
		}

		@Override
		public void link(Target t, List<ParseTree> children) throws ConfigCompileException {
			String name = children.get(0).getData().val();
			try {
				EventUtils.verifyEventName(name);
			} catch (IllegalArgumentException ex) {
				throw new ConfigCompileException(ex.getMessage(), t);
			}
		}

	}

	@api
	public static class dump_events extends AbstractFunction {

		@Override
		public String getName() {
			return "dump_events";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{0};
		}

		@Override
		public String docs() {
			return "array {} Returns an array of all the events currently registered on the server. Mostly meant for debugging,"
					+ " however it would be possible to parse this response to cherry pick events to unregister.";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}

		@Override
		public Boolean runAsync() {
			return null;
		}

		@Override
		public Construct exec(Target t, Environment env, Construct... args) throws ConfigRuntimeException {
			return EventUtils.DumpEvents();
		}
	}

	@api(environments = CommandHelperEnvironment.class)
	public static class unbind extends AbstractFunction {

		@Override
		public String getName() {
			return "unbind";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{0, 1};
		}

		@Override
		public String docs() {
			return "void {[eventID]} Unbinds an event, which causes it to not run anymore. If called from within an event handler, eventID is"
					+ " optional, and defaults to the current event id.";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.BindException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}

		@Override
		public Boolean runAsync() {
			return null;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			String id = null;
			if (args.length == 1) {
				//We are cancelling an arbitrary event
				id = args[0].val();
			} else {
				//We are cancelling this event. If we are not in an event, throw an exception
				if (environment.getEnv(GlobalEnv.class).GetEvent() == null) {
					throw new ConfigRuntimeException("No event ID specified, and not running inside an event", ExceptionType.BindException, t);
				}
				id = environment.getEnv(GlobalEnv.class).GetEvent().getBoundEvent().getId();
			}
			BoundEvent be = EventUtils.GetEventById(id);
			Event event = null;
			if (be != null) {
				event = be.getEventDriver();
			}
			EventUtils.UnregisterEvent(id);
			//Only remove the counter if it had been added in the first place.
			if (event != null && event.addCounter()) {
				synchronized (bindCounter) {
					bindCounter.decrementAndGet();
					if (bindCounter.get() == 0) {
						environment.getEnv(GlobalEnv.class).GetDaemonManager().deactivateThread(null);
					}
				}
			}
			return CVoid.VOID;
		}
	}

	@api(environments = CommandHelperEnvironment.class)
	public static class cancel extends AbstractFunction {

		@Override
		public String getName() {
			return "cancel";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{0, 1};
		}

		@Override
		public String docs() {
			return "void {[state]} Cancels the event (if applicable). If the event is not cancellable, or is already set to the specified"
					+ " cancelled state, nothing happens."
					+ " If called from outside an event handler, a BindException is thrown. By default, state is true, but you can"
					+ " uncancel an event (if possible) by calling cancel(false).";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.BindException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}

		@Override
		public Boolean runAsync() {
			return null;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			boolean cancelled = true;
			if (args.length == 1) {
				cancelled = Static.getBoolean(args[0]);
			}

			BoundEvent.ActiveEvent original = environment.getEnv(GlobalEnv.class).GetEvent();
			if (original == null) {
				throw new ConfigRuntimeException("cancel cannot be called outside an event handler", ExceptionType.BindException, t);
			}
			if (original.getUnderlyingEvent() != null && original.isCancellable()) {
				original.setCancelled(cancelled);
			}
			return CVoid.VOID;
		}
	}

	@api(environments = CommandHelperEnvironment.class)
	public static class is_cancelled extends AbstractFunction {

		@Override
		public String getName() {
			return "is_cancelled";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{0};
		}

		@Override
		public String docs() {
			return "boolean {} Returns whether or not the underlying event is cancelled or not. If the event is not cancellable in the first place,"
					+ " false is returned. If called from outside an event, a BindException is thrown";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.BindException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}

		@Override
		public Boolean runAsync() {
			return false;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			BoundEvent.ActiveEvent original = environment.getEnv(GlobalEnv.class).GetEvent();
			if (original == null) {
				throw new ConfigRuntimeException("is_cancelled cannot be called outside an event handler", ExceptionType.BindException, t);
			}
			boolean result = false;
			if (original.getUnderlyingEvent() != null && original.isCancellable()) {
				result = original.isCancelled();
			}
			return CBoolean.get(result);
		}
	}

	@api
	public static class trigger extends AbstractFunction {

		@Override
		public String getName() {
			return "trigger";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{2, 3};
		}

		@Override
		public String docs() {
			return "void {eventName, eventObject, [serverWide]} Manually triggers bound events. The event object passed to this function is "
					+ " sent directly as-is to the bound events. Check the documentation for each event to see what is required."
					+ " No checks will be done on the data here, but it is not recommended to fail to send all parameters required."
					+ " If serverWide is true, the event is triggered directly in the server, unless it is a CommandHelper specific"
					+ " event, in which case, serverWide is irrelevant. Defaults to false, which means that only CommandHelper code"
					+ " will receive the event.";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.CastException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}

		@Override
		public Boolean runAsync() {
			return false;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			CArray obj = null;
			if (args[1] instanceof CNull) {
				obj = new CArray(t);
			} else if (args[1] instanceof CArray) {
				obj = (CArray) args[1];
			} else {
				throw new ConfigRuntimeException("The eventObject must be null, or an array", ExceptionType.CastException, t);
			}
			boolean serverWide = false;
			if (args.length == 3) {
				serverWide = Static.getBoolean(args[2]);
			}
			EventUtils.ManualTrigger(args[0].val(), obj, t, serverWide);
			return CVoid.VOID;
		}
	}

	@api(environments = CommandHelperEnvironment.class)
	public static class modify_event extends AbstractFunction {

		@Override
		public String getName() {
			return "modify_event";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{2};
		}

		@Override
		public String docs() {
			return "boolean {parameter, value, [throwOnFailure]} Modifies the underlying event object, if applicable."
					+ " The documentation for each event will explain what parameters can be modified,"
					+ " and what their expected values are. ---- If an invalid parameter name is passed in,"
					+ " nothing will happen. If this function is called from outside an event"
					+ " handler, a BindException is thrown. Note that modifying the underlying event"
					+ " will NOT update the event object passed in to the event handler. The function returns"
					+ " whether or not the parameter was updated successfully. It could fail to modify the"
					+ " event if a higher priority handler has locked this parameter, or if updating the underlying"
					+ " event failed. If throwOnFailure is true, instead of returning false, it will throw"
					+ " a BindException. The default for throwOnFailure is false. If a monitor level handler"
					+ " even attempts to modify an event, an exception will be thrown.";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.CastException, ExceptionType.BindException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}

		@Override
		public Boolean runAsync() {
			return false;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			String parameter = args[0].val();
			Construct value = args[1];
			boolean throwOnFailure = false;
			if (args.length == 3) {
				throwOnFailure = Static.getBoolean(args[3]);
			}
			if (environment.getEnv(GlobalEnv.class).GetEvent() == null) {
				throw new ConfigRuntimeException(this.getName() + " must be called from within an event handler", ExceptionType.BindException, t);
			}
			Event e = environment.getEnv(GlobalEnv.class).GetEvent().getEventDriver();
			if (environment.getEnv(GlobalEnv.class).GetEvent().getBoundEvent().getPriority().equals(Priority.MONITOR)) {
				throw new ConfigRuntimeException("Monitor level handlers may not modify an event!", ExceptionType.BindException, t);
			}
			ActiveEvent active = environment.getEnv(GlobalEnv.class).GetEvent();
			boolean success = false;
			if (!active.isLocked(parameter)) {
				try {
					success = e.modifyEvent(parameter, value, environment.getEnv(GlobalEnv.class).GetEvent().getUnderlyingEvent());
				} catch (ConfigRuntimeException ex) {
					ex.setFile(t.file());
					ex.setLineNum(t.line());
					ex.setColumn(t.col());
					throw ex;
				}
			} else {
				success = false;
			}
			if (throwOnFailure && !success) {
				throw new ConfigRuntimeException("Event parameter is already locked!", ExceptionType.BindException, t);
			}
			return CBoolean.get(success);
		}
	}

	@api(environments = CommandHelperEnvironment.class)
	public static class lock extends AbstractFunction {

		@Override
		public String getName() {
			return "lock";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{Integer.MAX_VALUE};
		}

		@Override
		public String docs() {
			return "void {<none> | parameterArray | parameter, [parameter...]} Locks the specified event parameter(s), or all of them,"
					+ " if specified with no arguments. Locked parameters become read only for lower priority event handlers.";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.BindException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public Boolean runAsync() {
			return false;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			if (environment.getEnv(GlobalEnv.class).GetEvent() == null) {
				throw new ConfigRuntimeException("lock must be called from within an event handler", ExceptionType.BindException, t);
			}

			BoundEvent.ActiveEvent e = environment.getEnv(GlobalEnv.class).GetEvent();
			Priority p = e.getBoundEvent().getPriority();
			List<String> params = new ArrayList<String>();
			if (args.length == 0) {
				e.lock(null);
			} else {
				if (args[0] instanceof CArray) {
					CArray ca = (CArray) args[1];
					for (int i = 0; i < ca.size(); i++) {
						params.add(ca.get(i, t).val());
					}
				} else {
					for (Construct arg : args) {
						params.add(arg.val());
					}
				}
			}
			for (String param : params) {
				e.lock(param);
			}
			return CVoid.VOID;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}
	}

	@api(environments = CommandHelperEnvironment.class)
	public static class is_locked extends AbstractFunction {

		@Override
		public String getName() {
			return "is_locked";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{1};
		}

		@Override
		public String docs() {
			return "boolean {parameter} Returns whether or not a call to modify_event() would fail, based on"
					+ " the parameter being locked by a higher priority handler. If this returns false, it"
					+ " is still not a guarantee that the event would be successfully modified, just that"
					+ " it isn't locked.";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.BindException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public Boolean runAsync() {
			return false;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			if (environment.getEnv(GlobalEnv.class).GetEvent() == null) {
				throw new ConfigRuntimeException("is_locked may only be called from inside an event handler", ExceptionType.BindException, t);
			}
			return CBoolean.get(environment.getEnv(GlobalEnv.class).GetEvent().isLocked(args[0].val()));
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}
	}

	@api(environments = CommandHelperEnvironment.class)
	public static class consume extends AbstractFunction {

		@Override
		public String getName() {
			return "consume";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{0};
		}

		@Override
		public String docs() {
			return "void {} Consumes an event, so that lower priority handlers don't even"
					+ " recieve the event. Monitor level handlers will still recieve it, however,"
					+ " and they can check to see if the event was consumed.";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.BindException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public Boolean runAsync() {
			return false;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			if (environment.getEnv(GlobalEnv.class).GetEvent() == null) {
				throw new ConfigRuntimeException("consume may only be called from an event handler!", ExceptionType.BindException, t);
			}
			environment.getEnv(GlobalEnv.class).GetEvent().consume();
			return CVoid.VOID;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}
	}

	@api(environments = CommandHelperEnvironment.class)
	public static class is_consumed extends AbstractFunction {

		@Override
		public String getName() {
			return "is_consumed";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{0};
		}

		@Override
		public String docs() {
			return "boolean {} Returns whether or not this event has been consumed. Usually only useful"
					+ " for Monitor level handlers, it could also be used for highly robust code,"
					+ " as an equal priority handler could have consumed the event, but this handler"
					+ " would still recieve it.";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.BindException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public Boolean runAsync() {
			return false;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			if (environment.getEnv(GlobalEnv.class).GetEvent() == null) {
				throw new ConfigRuntimeException("is_consumed must be called from within an event handler", ExceptionType.BindException, t);
			}
			return CBoolean.get(environment.getEnv(GlobalEnv.class).GetEvent().isConsumed());
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}
	}

//    @api public static class when_triggered extends AbstractFunction{
//
//    }
//    @api public static class when_cancelled extends AbstractFunction{
//
//    }
	@api(environments = CommandHelperEnvironment.class)
	public static class event_meta extends AbstractFunction {

		@Override
		public String getName() {
			return "event_meta";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{0};
		}

		@Override
		public String docs() {
			return "array {} Returns meta information about the activity in regards to this event. This"
					+ " is meant as a debug tool.";
		}

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.BindException};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public Boolean runAsync() {
			return false;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			if (environment.getEnv(GlobalEnv.class).GetEvent() == null) {
				throw new ConfigRuntimeException("event_meta must be called from within an event handler!", ExceptionType.BindException, t);
			}
			CArray history = new CArray(t);
			for (String entry : environment.getEnv(GlobalEnv.class).GetEvent().getHistory()) {
				history.push(new CString(entry, t));
			}
			return history;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_0;
		}
	}

	@api
	public static class has_bind extends AbstractFunction {

		@Override
		public ExceptionType[] thrown() {
			return new ExceptionType[]{};
		}

		@Override
		public boolean isRestricted() {
			return true;
		}

		@Override
		public Boolean runAsync() {
			return null;
		}

		@Override
		public Construct exec(Target t, Environment environment, Construct... args) throws ConfigRuntimeException {
			String id = args[0].val();
			for (Driver d : Driver.values()) {
				Set<BoundEvent> events = EventUtils.GetEvents(d);
				if (events != null) {
					for (BoundEvent b : events) {
						if (b.getId().equals(id)) {
							return CBoolean.TRUE;
						}
					}
				}
			}
			return CBoolean.FALSE;
		}

		@Override
		public String getName() {
			return "has_bind";
		}

		@Override
		public Integer[] numArgs() {
			return new Integer[]{1};
		}

		@Override
		public String docs() {
			return "boolean {id} Returns true if a bind with the specified id exists and is"
					+ " currently bound. False is returned otherwise. This can be used to"
					+ " pre-emptively avoid a BindException if duplicate ids are used.";
		}

		@Override
		public Version since() {
			return CHVersion.V3_3_1;
		}

	}

}
