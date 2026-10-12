package io.github.devicebridge.core;
import java.util.*;
/** Immutable selection/approval envelope, tied to the exact session object, not its address. */
public final class SessionBinding<T> {
 private final T session;private final List<String> objects;
 public SessionBinding(T session,List<String> objects){if(session==null)throw new IllegalArgumentException("Missing session");this.session=session;this.objects=Collections.unmodifiableList(new ArrayList<>(objects));}
 public T require(T current){if(current!=session)throw new IllegalStateException("Device session changed; select and confirm again");return session;}
 public List<String> objects(){return objects;}
 /** A dialog opened on one view must not gain authority from a later selected device/page. */
 public static boolean matchesView(Object bound,Object selected,Object displayed,int capturedGeneration,int currentGeneration,boolean live){return live&&bound!=null&&bound==selected&&bound==displayed&&capturedGeneration==currentGeneration;}
}
