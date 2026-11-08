// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
namespace D325SdWriter;

internal sealed class MainForm : Form
{
    private static readonly Color Background = Color.FromArgb(16, 27, 44);
    private static readonly Color Surface = Color.FromArgb(11, 19, 32);
    private static readonly Color Accent = Color.FromArgb(67, 229, 196);
    private static readonly Color Foreground = Color.FromArgb(237, 245, 250);
    private readonly TextBox image = new() { ReadOnly = true, Dock = DockStyle.Fill };
    private readonly ComboBox targets = new() { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
    private readonly TextBox confirmation = new() { Dock = DockStyle.Fill, PlaceholderText = "ERASE PHYSICALDRIVE1" };
    private readonly ProgressBar progress = new() { Dock = DockStyle.Fill };
    private readonly Label status = new() { AutoSize = true, MaximumSize = new Size(720, 0), ForeColor = Foreground };
    private readonly Button verify = new() { Text = "2. VERIFY IMAGE", Enabled = false, AutoSize = true };
    private readonly Button refresh = new() { Text = "3. FIND SD / USB", Enabled = false, AutoSize = true };
    private readonly Button write = new() { Text = "4. ERASE AND WRITE", Enabled = false, AutoSize = true };
    private string? verifiedPath;

    internal MainForm()
    {
        Text = "D325f SDR — SD Writer";
        BackColor = Background; ForeColor = Foreground; Font = new Font("Segoe UI", 10); MinimumSize = new Size(760, 560);
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(24), AutoScroll = true, ColumnCount = 2 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize)); layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        var title = new Label { Text = "D325f SDR  //  SD WRITER", ForeColor = Accent, Font = new Font("Segoe UI Semibold", 22), AutoSize = true };
        AddWide(layout, title);
        AddWide(layout, new Label { Text = "Writes only the published sanitized LG D325 image. The image stays separate from this EXE. Administrator rights are required; the Windows system disk and PhysicalDrive0 are never offered.", ForeColor = Foreground, AutoSize = true, MaximumSize = new Size(700, 0), Padding = new Padding(0, 4, 0, 14) });
        var choose = Styled("1. SELECT .IMG"); choose.Click += ChooseImage; layout.Controls.Add(choose); layout.Controls.Add(image);
        Style(verify); verify.Click += VerifyImage; layout.Controls.Add(verify); layout.Controls.Add(new Label { Text = $"Expected SHA-256: {RawImage.Sha256}", ForeColor = Foreground, AutoSize = true });
        Style(refresh); refresh.Click += RefreshTargets; layout.Controls.Add(refresh); layout.Controls.Add(targets);
        Style(write); write.Click += WriteImage; layout.Controls.Add(write); layout.Controls.Add(confirmation);
        AddWide(layout, progress); AddWide(layout, status);
        AddWide(layout, new Label { Text = "The EXE writes and verifies the full image, expands GPT userdata and leaves it blank. The SD boot ramdisk has formattable enabled, so Lineage creates ext4 on first boot. The first boot can take 10–20 minutes.", ForeColor = Color.FromArgb(174, 255, 241), AutoSize = true, MaximumSize = new Size(700, 0), Padding = new Padding(0, 18, 0, 0) });
        Controls.Add(layout);
    }

    private void ChooseImage(object? sender, EventArgs e)
    {
        using var dialog = new OpenFileDialog { Filter = "Raw disk image (*.img)|*.img", CheckFileExists = true };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        image.Text = dialog.FileName; verifiedPath = null; verify.Enabled = true; refresh.Enabled = write.Enabled = false;
        status.Text = "Image selected. No disk has been changed.";
    }

    private async void VerifyImage(object? sender, EventArgs e) => await Guarded(async () =>
    {
        status.Text = "Verifying every image byte…"; Toggle(false);
        await RawImage.VerifyAsync(image.Text, ProgressReporter(), CancellationToken.None);
        verifiedPath = image.Text; refresh.Enabled = true; status.Text = "Image verified. It contains the fixed sanitized D325 SHA-256.";
    });

    private void RefreshTargets(object? sender, EventArgs e)
    {
        try {
            targets.DataSource = DiskDiscovery.FindTargets();
            write.Enabled = verifiedPath == image.Text && targets.Items.Count > 0;
            status.Text = targets.Items.Count == 0 ? "No safe removable SD/USB target found." : "Select the exact target, then type ERASE PHYSICALDRIVE<number>.";
        } catch (Exception failure) { ShowFailure(failure); }
    }

    private async void WriteImage(object? sender, EventArgs e) => await Guarded(async () =>
    {
        if (verifiedPath != image.Text) throw new InvalidOperationException("Verify the image first.");
        if (targets.SelectedItem is not DiskTarget target) throw new InvalidOperationException("Select an SD/USB disk.");
        var expected = $"ERASE PHYSICALDRIVE{target.Index}";
        if (confirmation.Text.Trim() != expected) throw new InvalidOperationException($"Type exactly: {expected}");
        confirmation.Clear(); Toggle(false);
        await DiskWriter.WriteAsync(image.Text, target, ProgressReporter(), new Progress<string>(s => status.Text = s), CancellationToken.None);
        write.Enabled = false;
    });

    private async Task Guarded(Func<Task> action)
    {
        try { await action(); } catch (Exception failure) { ShowFailure(failure); }
        finally { verify.Enabled = image.TextLength > 0; refresh.Enabled = verifiedPath == image.Text; }
    }
    private void ShowFailure(Exception failure) { status.Text = "ERROR: " + failure.Message; MessageBox.Show(this, failure.Message, "D325 SD Writer", MessageBoxButtons.OK, MessageBoxIcon.Error); }
    private Progress<(long, long)> ProgressReporter() => new(value => { progress.Maximum = 10_000; progress.Value = (int)(value.Item1 * 10_000L / value.Item2); status.Text = $"{value.Item1 / 1_048_576.0:F1} / {value.Item2 / 1_048_576.0:F1} MiB"; });
    private void Toggle(bool enabled) { verify.Enabled = refresh.Enabled = write.Enabled = enabled; }
    private static Button Styled(string text) { var button = new Button { Text = text, AutoSize = true }; Style(button); return button; }
    private static void Style(Button button) { button.BackColor = Accent; button.ForeColor = Surface; button.FlatStyle = FlatStyle.Flat; button.FlatAppearance.BorderSize = 0; button.Padding = new Padding(8); }
    private static void AddWide(TableLayoutPanel layout, Control control) { layout.Controls.Add(control); layout.SetColumnSpan(control, 2); }
}
