#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "rtklib.h"
#include "walksafe_ppk_engine.h"

typedef struct {
    walksafe_cancel_token_t *token;
    int result;
} cancel_test_t;

static void *wait_for_cancel(void *argument) {
    cancel_test_t *test = argument;
    test->result = walksafe_ppk_test_wait_for_inflight_cancel(test->token);
    return NULL;
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "--rinex") == 0) {
        int summary[4] = {0};
        const int result = walksafe_rinex_observation_summary(argv[2], summary);
        printf(
            "RINEX_PARSE=%s EPOCHS=%d SATELLITES=%d PSEUDORANGE=%d PHASE=%d\n",
            result == 0 ? "PASS" : "FAIL", summary[0], summary[1], summary[2], summary[3]
        );
        return result == 0 ? 0 : 10;
    }
    if (argc != 6) return 2;
    int observation_summary[4] = {0};
    if (walksafe_rinex_observation_summary(argv[1], observation_summary) != 0) return 6;
    const char *inputs[] = {argv[1], argv[2], argv[2], argv[3]};
    walksafe_cancel_token_t *normal_token = walksafe_cancel_token_create(1001);
    if (normal_token == NULL || walksafe_ppk_run(inputs, 4, 2, argv[4], normal_token) != 0) return 3;

    FILE *output = fopen(argv[4], "rb");
    if (output == NULL) return 4;
    char line[4096];
    int has_utc_llh_header = 0;
    int fixed_epochs = 0;
    while (fgets(line, sizeof(line), output) != NULL) {
        if (line[0] == '%' && strstr(line, "UTC") != NULL && strstr(line, "latitude(deg)") != NULL) {
            has_utc_llh_header = 1;
        }
        if (line[0] != '%') {
            int quality = 0;
            if (sscanf(line, "%*s %*s %*f %*f %*f %d", &quality) == 1 && quality == 1) {
                fixed_epochs++;
            }
        }
    }
    fclose(output);
    if (!has_utc_llh_header || fixed_epochs == 0) return 5;

    const char *collision_inputs[] = {argv[1], argv[2], argv[5]};
    if (walksafe_ppk_run(collision_inputs, 3, 1, argv[4], normal_token) == 0) return 7;

    char oversized_output[MAXSTRPATH + 32];
    memset(oversized_output, 'x', sizeof(oversized_output) - 1);
    oversized_output[sizeof(oversized_output) - 1] = '\0';
    if (walksafe_ppk_run(inputs, 4, 2, oversized_output, normal_token) == 0) return 8;
    if (walksafe_cancel_token_destroy(normal_token, 1001) != 0) return 9;

    walksafe_cancel_token_t *pre_cancelled = walksafe_cancel_token_create(1002);
    if (pre_cancelled == NULL || walksafe_cancel_token_cancel(pre_cancelled, 1002) != 0 ||
        walksafe_ppk_run(inputs, 4, 2, argv[4], pre_cancelled) != WALKSAFE_PPK_CANCELLED ||
        walksafe_cancel_token_destroy(pre_cancelled, 1002) != 0) return 11;

    walksafe_cancel_token_t *inflight = walksafe_cancel_token_create(1003);
    cancel_test_t cancel_test = {inflight, 0};
    pthread_t cancel_thread;
    if (inflight == NULL || pthread_create(&cancel_thread, NULL, wait_for_cancel, &cancel_test) != 0) return 12;
    const struct timespec pause = {0, 20 * 1000 * 1000};
    nanosleep(&pause, NULL);
    if (walksafe_cancel_token_cancel(inflight, 1003) != 0 ||
        pthread_join(cancel_thread, NULL) != 0 || cancel_test.result != WALKSAFE_PPK_CANCELLED ||
        walksafe_cancel_token_destroy(inflight, 1003) != 0) return 13;
    printf(
        "RINEX_PARSE=PASS EPOCHS=%d SATELLITES=%d PSEUDORANGE=%d PHASE=%d UTC_LLH=PASS Q1_EPOCHS=%d\n",
        observation_summary[0], observation_summary[1], observation_summary[2],
        observation_summary[3], fixed_epochs
    );
    return 0;
}
