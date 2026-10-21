#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sched.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>

static int check_flag(const char *name) {
    char p1[256], p2[256], p3[256];
    snprintf(p1, sizeof(p1), "/root/.dsh/%s", name);
    snprintf(p2, sizeof(p2), "/sdcard/Download/DSHA/%s", name);
    snprintf(p3, sizeof(p3), "/data/data/com.dsha.fr/files/%s", name);
    return (access(p1, F_OK) == 0 || access(p2, F_OK) == 0 || access(p3, F_OK) == 0);
}

#define LEASE_FILE "/root/.dsh/.auth_lease"
#define DECISION_FILE "/data/data/com.dsha.fr/files/.approval_decision"
#define BACKUP_DECISION_FILE "/data/adb/dsha/rootfs/root/.dsh/.approval_decision"

static void post_system_notification(const char *title, const char *text) {
    pid_t pid = fork();
    if (pid == 0) {
        int fd = open("/proc/1/ns/mnt", O_RDONLY);
        if (fd >= 0) {
            setns(fd, 0);
            close(fd);
        }
        // 切成 uid 2000 (com.android.shell)，让 Android 17 系统服务完美识别并放行
        if (setgid(2000) != 0 || setuid(2000) != 0) {}
        execl("/system/bin/cmd", "cmd", "notification", "post", "-S", "bigtext",
              "-t", title ? title : "DSHA", "dsha_live", text ? text : "任务运行中", (char *)NULL);
        _exit(1);
    }
    int status;
    waitpid(pid, &status, 0);
}

static void cancel_system_notification(void) {
    pid_t pid = fork();
    if (pid == 0) {
        int fd = open("/proc/1/ns/mnt", O_RDONLY);
        if (fd >= 0) {
            setns(fd, 0);
            close(fd);
        }
        if (setgid(2000) != 0 || setuid(2000) != 0) {}
        execl("/system/bin/cmd", "cmd", "notification", "snooze", "--for", "999999999",
              "0|com.android.shell|2020|dsha_live|2000", (char *)NULL);
        _exit(1);
    }
    int status;
    waitpid(pid, &status, 0);
}

static void broadcast_task(const char *type, const char *title, const char *text) {
    // 1. 若用户在设置开启了「后端独立直发通知」，由底层 C 原生程序以 UID 2000 (com.android.shell) 直接发射系统级通知！
    //    完全脱离 DSHA APK，哪怕 APK 进程被杀或被冻结，通知依然坚挺发出！
    if (check_flag(".backend_notify")) {
        if (strcmp(type, "cancel") == 0) {
            cancel_system_notification();
        } else {
            post_system_notification(title, text);
        }
    }

    // 2. 同时向系统发送显式广播（若 App 活着，同步更新悬浮条、抽屉横幅与网页小黄窗）
    char cmd[1024];
    snprintf(cmd, sizeof(cmd),
             "am broadcast -a com.deepseekharness.app.TASK_NOTIFY -n com.dsha.fr/.TaskNotifyReceiver "
             "--es type '%s' --es title '%s' --es text '%s' >/dev/null 2>&1",
             type ? type : "running",
             title ? title : "",
             text ? text : "");
    system(cmd);
}

static int is_safe_cleanup(const char *cmd_name, int argc, char **argv) {
    if (strcmp(cmd_name, "rm") != 0 && strcmp(cmd_name, "unlink") != 0) return 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-r") == 0 || strcmp(argv[i], "-R") == 0 || strstr(argv[i], "*")) return 0;
        if (strcmp(argv[i], "-f") == 0 || strcmp(argv[i], "-v") == 0) continue;
        if (strstr(argv[i], "/tmp/") == argv[i] ||
            strstr(argv[i], ".png") != NULL ||
            strstr(argv[i], ".jpg") != NULL ||
            strstr(argv[i], ".tmp") != NULL ||
            strstr(argv[i], "/root/.dsh/.auth_lease") != NULL) {
            continue;
        }
        return 0;
    }
    return 1;
}

static int check_lease(void) {
    struct stat st;
    if (stat(LEASE_FILE, &st) == 0) {
        FILE *f = fopen(LEASE_FILE, "r");
        if (f) {
            long exp = 0;
            if (fscanf(f, "%ld", &exp) == 1) {
                time_t now = time(NULL);
                fclose(f);
                if (now < exp) return 1;
            } else {
                fclose(f);
            }
        }
    }
    return 0;
}

static int wait_decision(long long epoch) {
    (void)epoch;
    for (int i = 0; i < 300; i++) {
        struct stat st;
        const char *target = NULL;
        if (stat(DECISION_FILE, &st) == 0) target = DECISION_FILE;
        else if (stat(BACKUP_DECISION_FILE, &st) == 0) target = BACKUP_DECISION_FILE;

        if (target) {
            FILE *f = fopen(target, "r");
            if (f) {
                char buf[64] = {0};
                if (fgets(buf, sizeof(buf), f)) {
                    fclose(f);
                    unlink(DECISION_FILE);
                    unlink(BACKUP_DECISION_FILE);
                    if (strstr(buf, "allowed") || strstr(buf, "allow")) return 1;
                    return 0;
                }
                fclose(f);
            }
        }
        usleep(200000); // 200ms
    }
    return 0; // 超时拒绝
}

static void handle_guard_wrapper(const char *prog, int argc, char **argv) {
    // 1. 微秒级判定：若 App 设置中未开启“危险 Shell 操作需确认”，0 额外进程，直接放行！
    if (!check_flag(".guard_enabled") && getenv("DSH_CONFIRM") == NULL) {
        // 放行
    } else if (check_lease() || is_safe_cleanup(prog, argc, argv)) {
        // 临时清理免审放行
    } else {
        // 组装命令文本
        char full_cmd[1024] = {0};
        snprintf(full_cmd, sizeof(full_cmd), "%s", prog);
        for (int i = 1; i < argc && strlen(full_cmd) < sizeof(full_cmd) - 64; i++) {
            strncat(full_cmd, " ", sizeof(full_cmd) - strlen(full_cmd) - 1);
            strncat(full_cmd, argv[i], sizeof(full_cmd) - strlen(full_cmd) - 1);
        }

        long long epoch = (long long)time(NULL) * 1000LL;
        unlink(DECISION_FILE);
        unlink(BACKUP_DECISION_FILE);

        // 1. 若开启了后端独立直发通知，由底层 C 原生程序以 UID 2000 (com.android.shell) 直接发射系统级危险确认通知！
        int is_backend = check_flag(".backend_notify");
        if (is_backend) {
            char confirm_text[1100];
            snprintf(confirm_text, sizeof(confirm_text), "模型请求执行高危命令: %s", full_cmd);
            post_system_notification("⚠️ 危险命令确认", confirm_text);
        }

        // 2. 发送四端统一唤起广播（唤起通知栏、悬浮条、抽屉横幅、网页小黄窗）
        char bcmd[1280];
        snprintf(bcmd, sizeof(bcmd),
                 "am broadcast -a com.deepseekharness.app.SHELL_CONFIRM_REQUEST -n com.dsha.fr/.TaskNotifyReceiver "
                 "--es cmd '%s' --el epoch %lld >/dev/null 2>&1", full_cmd, epoch);
        system(bcmd);

        int ok = wait_decision(epoch);

        // 3. 决断完成后，自动清理系统级确认通知
        if (is_backend) {
            cancel_system_notification();
        }

        if (!ok) {
            fprintf(stderr, "已拒绝执行高危命令: %s (用户拒绝或超时)\n", full_cmd);
            exit(1);
        }
    }

    // 寻找真实系统命令执行
    const char *paths[] = {"/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin", NULL};
    for (int i = 0; paths[i]; i++) {
        char real_path[256];
        snprintf(real_path, sizeof(real_path), "%s/%s", paths[i], prog);
        if (access(real_path, X_OK) == 0) {
            execv(real_path, argv);
        }
    }
    fprintf(stderr, "找不到真实命令: %s\n", prog);
    exit(127);
}

int main(int argc, char **argv) {
    char *prog = strrchr(argv[0], '/');
    prog = prog ? prog + 1 : argv[0];

    // 如果通过软链调用（如 rm、dd、reboot），进入守卫包装逻辑
    if (strcmp(prog, "rm") == 0 || strcmp(prog, "rmdir") == 0 ||
        strcmp(prog, "unlink") == 0 || strcmp(prog, "truncate") == 0 ||
        strcmp(prog, "dd") == 0 || strcmp(prog, "mkfs") == 0 ||
        strcmp(prog, "mkfs.ext4") == 0 || strcmp(prog, "mkfs.vfat") == 0 ||
        strcmp(prog, "fdisk") == 0 || strcmp(prog, "reboot") == 0 ||
        strcmp(prog, "shutdown") == 0 || strcmp(prog, "halt") == 0 ||
        strcmp(prog, "poweroff") == 0 || strcmp(prog, "wipe") == 0) {
        handle_guard_wrapper(prog, argc, argv);
        return 0;
    }

    // 否则作为统一通知收发工具: dsh-notify <running|done|ask|cancel> [title] [text]
    if (argc < 2) {
        fprintf(stderr, "用法: dsh-notify <running|done|ask|cancel> [title] [text]\n");
        return 1;
    }

    const char *action = argv[1];
    const char *title = (argc >= 3) ? argv[2] : "";
    const char *text  = (argc >= 4) ? argv[3] : "";

    broadcast_task(action, title, text);
    return 0;
}
