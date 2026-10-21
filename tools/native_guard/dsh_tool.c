#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <time.h>

#define GUARD_FLAG "/data/data/com.dsha.fr/files/.guard_enabled"
#define BACKEND_NOTIFY_FLAG "/data/data/com.dsha.fr/files/.backend_notify"
#define LEASE_FILE "/root/.dsh/.auth_lease"
#define DECISION_FILE "/data/data/com.dsha.fr/files/.approval_decision"
#define BACKUP_DECISION_FILE "/data/adb/dsha/rootfs/root/.dsh/.approval_decision"

static void broadcast_task(const char *type, const char *title, const char *text) {
    // 1. 若用户在主题与通知页面开启了「后端独立直发通知」，由底层直发系统通知（脱离 App 独立模式）
    if (access(BACKEND_NOTIFY_FLAG, F_OK) == 0) {
        char sys_cmd[1024];
        if (strcmp(type, "cancel") == 0) {
            // 系统命令行直接清理该通知
            system("cmd notification post dsha_backend_task '' >/dev/null 2>&1 || true");
        } else {
            snprintf(sys_cmd, sizeof(sys_cmd),
                     "cmd notification post -S bigtext -t '%s' dsha_backend_task '%s' >/dev/null 2>&1",
                     title ? title : "DSHA",
                     text ? text : "任务运行中");
            system(sys_cmd);
        }
    }

    // 2. 发送系统显式广播通知 App 调度中心：
    //    - 若为 App 默认模式：App 原地构建发出 3 种通知形态（普通/AOSP/HyperOS）；
    //    - 若已开启后端直发：App 自动抑制通知发送，但悬浮条、抽屉、网页小黄窗这 3 条通道依然照常同显！
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
    if (access(GUARD_FLAG, F_OK) != 0 && getenv("DSH_CONFIRM") == NULL) {
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

        // 1. 若开启了后端独立直发通知，后端直接向系统发射确认通知（原地替换运行状态）
        int is_backend = (access(BACKEND_NOTIFY_FLAG, F_OK) == 0);
        if (is_backend) {
            char sys_confirm[1280];
            snprintf(sys_confirm, sizeof(sys_confirm),
                     "cmd notification post -S bigtext -t '⚠️ 危险命令确认' dsha_backend_task '模型请求执行高危命令: %s' >/dev/null 2>&1",
                     full_cmd);
            system(sys_confirm);
        }

        // 2. 发送四端统一唤起广播（唤起悬浮条、抽屉横幅、网页小黄窗）
        char bcmd[1280];
        snprintf(bcmd, sizeof(bcmd),
                 "am broadcast -a com.deepseekharness.app.SHELL_CONFIRM_REQUEST -n com.dsha.fr/.TaskNotifyReceiver "
                 "--es cmd '%s' --el epoch %lld >/dev/null 2>&1", full_cmd, epoch);
        system(bcmd);

        int ok = wait_decision(epoch);

        // 3. 决断完成后，清理后端直发的确认通知
        if (is_backend) {
            system("cmd notification post dsha_backend_task '' >/dev/null 2>&1 || true");
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
