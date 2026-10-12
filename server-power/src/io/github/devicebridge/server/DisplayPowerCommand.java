package io.github.devicebridge.server;
import com.genymobile.scrcpy.Device;
/** Independent command wrapper around the licensed, source-built scrcpy physical-display API. */
public final class DisplayPowerCommand {
 public static void main(String[] args){if(args.length!=1||!("on".equals(args[0])||"off".equals(args[0]))){System.err.println("Expected on or off");System.exit(2);return;}try{boolean ok=Device.setScreenPowerMode("on".equals(args[0])?Device.POWER_MODE_NORMAL:Device.POWER_MODE_OFF);if(ok)System.out.println("POWER_REQUEST_SUBMITTED");else{System.err.println("Physical display API unavailable or permission denied");System.exit(3);}}catch(Throwable error){System.err.println("Physical display control unsupported: "+error.getClass().getSimpleName());System.exit(4);}}
}
