package limn.demo.site;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.backend.Cursor;
import limn.components.SizeTokens;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

/**
 * The worked example the custom widgets guide shows: a rating of zero to five dots that measures
 * and paints itself, takes a click and the arrow keys, tells a watcher and its handler what
 * changed, and is read by a screen reader as a value. Compiled by {@code ./gradlew check}, and
 * run by {@code CustomWidgetExampleTest}, which also holds it to {@code limn-test}'s value
 * contract, so what the guide says the widget does is what it does.
 */
public final class CustomWidgetExample {

    private CustomWidgetExample() {
    }

    /** Zero to five dots, set by a click, the arrow keys or a screen reader. */
    public static final class Rating extends Widget<Rating> {

        static final int MAX = 5;

        // #region guide:custom-widget-state
        private int value;
        private Runnable onChange;

        public Rating() {
            setFocusable(true);
            setCursor(Cursor.POINTER);
        }

        public int value() {
            return value;
        }

        /** Sets the rating from code: a watcher hears it, the handler does not. */
        public Rating setValue(int newValue) {
            Ui.checkUiThread();
            return apply(newValue, Change.Origin.CODE);
        }

        /** Runs when the user changes the rating; read {@link #value()} inside. */
        public Rating onChange(Runnable handler) {
            Ui.checkUiThread();
            if (onChange != null && handler != null) {
                throw new IllegalStateException("onChange already has a handler: pass null first");
            }
            onChange = handler;
            return this;
        }

        private Rating apply(int newValue, Change.Origin origin) {
            int clamped = Math.max(0, Math.min(MAX, newValue));
            if (clamped != value) {
                value = clamped;
                invalidate();                                        // same size, new picture
                notifyChange(Change.of(Change.Aspect.VALUE, origin)); // last, once settled
            }
            return this;
        }

        @Override
        protected void handleUserChange(Change.Aspect aspect) {
            if (aspect == Change.Aspect.VALUE) {
                if (onChange != null) {
                    onChange.run();
                }
            } else {
                super.handleUserChange(aspect);
            }
        }
        // #endregion

        // #region guide:custom-widget-draw
        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.of(this).tokensFor(this);   // here, never in the constructor
            float dot = t.controlHeight();
            return constraints.constrain(MAX * dot, dot);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            float dot = height();
            float radius = dot * 0.3f;
            for (int i = 0; i < MAX; i++) {
                int slot = isRightToLeft() ? MAX - 1 - i : i; // the first dot is where reading starts
                float cx = slot * dot + dot / 2;
                if (i < value) {
                    canvas.fillCircle(cx, dot / 2, radius, theme.primary());
                } else {
                    canvas.drawCircle(cx, dot / 2, radius, 1.5f, theme.outline());
                }
            }
            if (isFocused()) {
                canvas.drawRoundRect(0, 0, width(), height(), dot / 2, 2, theme.focusRing());
            }
        }
        // #endregion

        // #region guide:custom-widget-input
        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.PRESS && isEnabled()) {
                requestFocus();
                int slot = (int) (event.x() / height());
                int dot = isRightToLeft() ? MAX - 1 - slot : slot;
                apply(dot + 1, Change.Origin.USER);
                event.consume();
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (!event.isPressed() || !isEnabled()) {
                return;
            }
            int forward = isRightToLeft() ? Keys.LEFT : Keys.RIGHT;
            int back = isRightToLeft() ? Keys.RIGHT : Keys.LEFT;
            int key = event.key();
            if (key == forward || key == Keys.UP) {
                apply(value + 1, Change.Origin.USER);
                event.consume();
            } else if (key == back || key == Keys.DOWN) {
                apply(value - 1, Change.Origin.USER);
                event.consume();
            }
        }

        @Override
        protected void onFocusGained() {
            invalidate();
        }

        @Override
        protected void onFocusLost() {
            invalidate();
        }
        // #endregion

        // #region guide:custom-widget-a11y
        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.SLIDER);
            a.value(value, 0, MAX, 1, false);
            a.action(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT);
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
            if (!isEnabled()) {
                return false;
            }
            switch (action) {
                case INCREMENT -> apply(value + 1, Change.Origin.USER);
                case DECREMENT -> apply(value - 1, Change.Origin.USER);
                case SET_VALUE -> {
                    double asked = Accessible.Argument.finiteValueOf(arg);
                    if (Double.isNaN(asked)) {
                        return false;
                    }
                    apply((int) Math.round(asked), Change.Origin.USER);
                }
                default -> {
                    return false;
                }
            }
            return true;
        }
        // #endregion
    }
}
