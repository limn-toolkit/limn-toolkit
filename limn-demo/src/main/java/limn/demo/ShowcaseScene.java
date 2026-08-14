package limn.demo;

import limn.components.Button;
import limn.components.Dialog;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.ProgressBar;
import limn.components.Theme;
import limn.graphics.BitmapIcon;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * Showcase: images/icons (standalone {@link ImageView}, plus icons inside
 * {@link Button} and {@link Label}), {@link ProgressBar}s (determinate and
 * indeterminate), and a translucent modal {@link Dialog}.
 */
final class ShowcaseScene {

    record Built(Scene scene, java.util.function.Supplier<Dialog> openDialog) {
    }

    private ShowcaseScene() {
    }

    static Built create(boolean lightTheme) {
        Theme.setCurrent(lightTheme ? Theme.light() : Theme.dark());

        Image logo = Images.fromResource("/limn/demo/images/logo.png");
        Image star = Images.fromResource("/limn/demo/images/icon-star.png");
        // Monochrome PNG masks → tintable icons that follow the theme ink.
        Icon check = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-check.png"));
        Icon download = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-download.png"));
        Icon heart = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-heart.png"));
        Icon gear = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-gear.png"));

        Label status = new Label("Interact with the components…").setMuted(true);

        // Header: full-color logo image + title.
        Row header = new Row();
        header.gap(14).crossAlignment(Flex.CrossAlignment.CENTER);
        header.add(new SizedBox(48, 48, new ImageView(logo).setFit(ImageView.Fit.CONTAIN)));
        // Role, not setFont(theme.title): setFont would pin MEDIUM's 20 pt even when
        // the scene resolves to another step.
        header.add(new Label("Limn UI: images, icons and dialog").setRole(Label.Role.TITLE));

        // Icon buttons (icon tinted to the label color) + a labeled icon.
        Button save = new Button("Save").setIcon(check);
        save.onAction(() -> status.setText("Saved!"));
        Button dl = new Button("Download").setSecondary(true).setIcon(download);
        dl.onAction(() -> status.setText("Downloading…"));
        Row iconButtons = new Row();
        iconButtons.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        iconButtons.add(save);
        iconButtons.add(dl);
        iconButtons.add(new Label("Favorite").setIcon(heart));
        iconButtons.add(new Label("Settings").setIcon(gear).setMuted(true));
        // Standalone tinted icons in the accent color.
        iconButtons.add(new SizedBox(28, 28, new ImageView(star).setTint(Theme.current().primary)));

        // Progress bars.
        ProgressBar p30 = new ProgressBar().setProgress(0.30f);
        ProgressBar p70 = new ProgressBar().setProgress(0.70f);
        ProgressBar indeterminate = new ProgressBar();
        Column bars = new Column();
        bars.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        bars.add(new Label("Progress 30% / 70% / indeterminate").setMuted(true));
        bars.add(p30);
        bars.add(p70);
        bars.add(indeterminate);

        // Dialog trigger.
        Button openDialog = new Button("Open modal dialog").setIcon(gear);

        Column content = new Column();
        content.gap(18).crossAlignment(Flex.CrossAlignment.START);
        content.add(header);
        content.add(iconButtons);
        content.add(new SizedBox(360, SizedBox.UNSET, bars));
        content.add(openDialog);
        content.add(new SizedBox(SizedBox.UNSET, 18, status));

        Scene scene = new Scene(limn.scene.layout.Padding.all(22, content));
        scene.setBackground(Theme.current().background);

        // Now that widgets are in a scene, the indeterminate bar can animate.
        indeterminate.setIndeterminate(true);

        java.util.function.Supplier<Dialog> openDialogAction = () -> {
            Dialog dialog = new Dialog("Remove item?",
                    "This action cannot be undone. The item will be removed permanently.")
                    .addButton("Cancel", "cancel")
                    .addPrimaryButton("Remove", "delete")
                    .setCancelResult("cancel");
            dialog.show(scene).thenAccept(result -> status.setText("Dialog: " + result));
            return dialog;
        };
        openDialog.onAction(openDialogAction::get);

        return new Built(scene, openDialogAction);
    }
}
