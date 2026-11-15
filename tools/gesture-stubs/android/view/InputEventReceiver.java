package android.view;
import android.os.Looper;
/** Compile-only Android hidden API signatures, excluded from the APK. */
public abstract class InputEventReceiver {
    public InputEventReceiver(InputChannel channel, Looper looper) { throw new AssertionError("stub"); }
    public void onInputEvent(InputEvent event) {}
    public final void finishInputEvent(InputEvent event, boolean handled) { throw new AssertionError("stub"); }
    public void dispose() { throw new AssertionError("stub"); }
}
