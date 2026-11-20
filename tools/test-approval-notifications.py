#!/usr/bin/env python3
"""Exercise production approval gate/decision method with harmless JVM stubs."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/deepseekharness/app'


def method(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


class ApprovalNotificationsTest(unittest.TestCase):
    def test_command_source_titles_and_hidden_ids(self):
        probe = r'''package com.deepseekharness.app;
class SourceProbe {
 static void check(boolean ok) { if (!ok) throw new AssertionError(); }
 public static void main(String[] args) {
   CommandApprovalSource main = new CommandApprovalSource("root-secret", "root-secret", "修复弹窗", "");
   check(!main.isChildAgent());
   check(main.getConversationTitle().equals("修复弹窗"));
   CommandApprovalSource child = new CommandApprovalSource("root-secret", "child-secret", "主对话", "检查代码");
   check(child.isChildAgent() && child.getAgentTitle().equals("检查代码"));
   check(child.getRootSessionId().equals("root-secret") && child.getSessionId().equals("child-secret"));
   check(!child.getConversationTitle().contains("secret") && !child.getAgentTitle().contains("secret"));
   CommandApprovalSource empty = new CommandApprovalSource("root-secret", "child-secret", null, " ");
   check(empty.getConversationTitle().equals("未命名对话") && empty.getAgentTitle().equals("未命名子代理"));
   check(CommandApprovalSource.UNKNOWN.getConversationTitle().equals("未知对话"));
   check(!CommandApprovalSource.UNKNOWN.isChildAgent());
   CommandApprovalSource multiline = new CommandApprovalSource("a", "b", "  第一行\n第二行\t标题\u202e  ", "测试\u2028子任务");
   check(multiline.getConversationTitle().equals("第一行 第二行 标题"));
   check(multiline.getAgentTitle().equals("测试 子任务"));
   String longTitle = "😀".repeat(250);
   String capped = new CommandApprovalSource("a", "a", longTitle, "").getConversationTitle();
   check(capped.codePointCount(0, capped.length()) == 201 && capped.endsWith("…"));
   check(capped.substring(0, capped.length()-1).equals("😀".repeat(200)));
 }
}'''
        with tempfile.TemporaryDirectory(prefix='dsha-source-test-') as directory:
            tmp = Path(directory)
            (tmp / 'SourceProbe.java').write_text(probe)
            subprocess.run(['javac', '-d', str(tmp), str(JAVA / 'CommandApprovalSource.java'),
                            str(tmp / 'SourceProbe.java')], check=True)
            subprocess.run(['java', '-cp', str(tmp), 'com.deepseekharness.app.SourceProbe'], check=True)

    def test_production_gate_and_decision(self):
        service = (JAVA / 'HttpShellService.java').read_text()
        decision = method(service, 'public boolean resolveConfirm(ShellDecision decision, long epoch)')
        harness = '''package com.deepseekharness.app;
import java.util.concurrent.CountDownLatch;
class Probe {
 enum ShellDecision { ALLOW_ONCE, ALLOW_10MIN, DENY }
 final ApprovalRequestGate approvalRequests = new ApprovalRequestGate();
 boolean pendingAllow; CountDownLatch pendingLatch;
 static final long UI_GRANT_MS = 600000; long lease; int dismissals;
 void grantAuthLease(long value) { lease = value; }
 void dismissAllApprovalUi() { dismissals++; }
 static class ConfirmReceiver {
   static int writes; static String result;
   static void writeApprovalDecision(String value, ApprovalRequestGate gate, long epoch) {
     gate.writeIfCurrent(epoch, () -> { writes++; result = value; });
   }
 }
 static void check(boolean ok) { if (!ok) throw new AssertionError(); }
 DECISION
 public static void main(String[] args) throws Exception {
   Probe p = new Probe(); long old = p.approvalRequests.begin();
   long active = p.approvalRequests.begin();
   check(!p.resolveConfirm(ShellDecision.ALLOW_10MIN, old));
   check(p.lease == 0 && ConfirmReceiver.writes == 0 && p.dismissals == 0);
   p.pendingLatch = new CountDownLatch(1);
   check(p.resolveConfirm(ShellDecision.ALLOW_10MIN, active));
   check(p.pendingAllow && p.lease == 600000 && p.pendingLatch.getCount() == 0);
   check("allowed-once".equals(ConfirmReceiver.result) && ConfirmReceiver.writes == 1);
   check(!p.resolveConfirm(ShellDecision.DENY, active));
   check(p.lease == 600000 && ConfirmReceiver.writes == 1);
   long expired = p.approvalRequests.begin(); p.approvalRequests.expire(expired);
   check(!p.resolveConfirm(ShellDecision.ALLOW_10MIN, expired));
   long denied = p.approvalRequests.begin();
   check(p.resolveConfirm(ShellDecision.DENY, denied));
   check(!p.pendingAllow && "rejected".equals(ConfirmReceiver.result));
   check(p.lease == 600000);
   long accepted = p.approvalRequests.begin(); check(p.approvalRequests.claim(accepted));
   p.approvalRequests.begin();
   p.approvalRequests.writeIfCurrent(accepted, () -> { throw new AssertionError("late write"); });
   p.approvalRequests.invalidate();
   check(!p.approvalRequests.claim(accepted));
   ApprovalRequestGate replacement = new ApprovalRequestGate();
   replacement.begin(); check(!replacement.claim(active));
   long concurrent = replacement.begin();
   java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
   Thread[] clicks = new Thread[32];
   for (int i=0;i<clicks.length;i++) {
     clicks[i] = new Thread(() -> { if (replacement.claim(concurrent)) winners.incrementAndGet(); });
     clicks[i].start();
   }
   for (Thread click : clicks) click.join();
   check(winners.get() == 1);
 }
}'''.replace('DECISION', decision)
        with tempfile.TemporaryDirectory(prefix='dsha-approval-test-') as directory:
            tmp = Path(directory)
            (tmp / 'ApprovalRequestGate.java').write_text((JAVA / 'ApprovalRequestGate.java').read_text())
            (tmp / 'Probe.java').write_text(harness)
            (tmp / 'Log.java').write_text('package android.util; public class Log { public static int i(String a,String b) { return 0; } }')
            subprocess.run(['javac', '-d', str(tmp), *map(str, tmp.glob('*.java'))], check=True)
            subprocess.run(['java', '-cp', str(tmp), 'com.deepseekharness.app.Probe'], check=True)

    def test_guard_mode_respects_lease_and_switch(self):
        service = (JAVA / 'HttpShellService.java').read_text()
        start = service.index('boolean needConfirm = force ||')
        expression = service[start:service.index(';', start) + 1]
        source = '''class LeaseProbe {
 static boolean enabled, dangerous, leased, isTaskWaived, isTaskActive;
 static boolean confirmEnabled() { return enabled; }
 static boolean isLeaseActive() { return leased; }
 static class DangerShellGuard { static boolean isDangerous(String c) { return dangerous; } }
 static boolean needs(String mode) {
  String cmd = "opaque terminal command";
  boolean force = "1".equals(mode), guarded = "2".equals(mode);
  EXPRESSION
  return needConfirm;
 }
 public static void main(String[] args) {
  enabled=true; dangerous=false;
  if (!needs("2")) throw new AssertionError("guard mode missed");
  leased=true;
  if (needs("2")) throw new AssertionError("guard mode broke lease");
  if (!needs("1")) throw new AssertionError("explicit force changed");
  leased=false; enabled=false;
  if (needs("2")) throw new AssertionError("guard ignored switch");
  enabled=true; isTaskWaived=true; isTaskActive=true;
  if (needs("2")) throw new AssertionError("guard ignored task waiver");
 }
}'''.replace('EXPRESSION', expression)
        with tempfile.TemporaryDirectory(prefix='dsha-lease-test-') as directory:
            tmp = Path(directory)
            file = tmp / 'LeaseProbe.java'
            file.write_text(source)
            subprocess.run(['javac', '-d', str(tmp), str(file)], check=True)
            subprocess.run(['java', '-cp', str(tmp), 'LeaseProbe'], check=True)

    def test_receiver_has_no_unvalidated_writes(self):
        text = (JAVA / 'ConfirmReceiver.java').read_text()
        receiver = method(text, 'public void onReceive(')
        self.assertNotIn('writeApprovalDecision(', receiver)
        self.assertNotIn('syncApprovalDecision(', receiver)
        self.assertIn('resolveConfirm(ShellDecision.ALLOW_10MIN, epoch)', receiver)

    def test_notification_identity_contains_epoch(self):
        text = (JAVA / 'HttpShellService.java').read_text()
        for signature in ('private void showConfirmNotification(', 'private void showApprovalWaitingNotification('):
            notification = method(text, signature)
            for declaration in notification.split('Intent ')[1:]:
                if 'new Intent(ctx, ConfirmReceiver.class)' in declaration:
                    self.assertIn('.setData(Uri.parse("dsha://approval/" + ', declaration.split(';')[0])


if __name__ == '__main__':
    unittest.main()
