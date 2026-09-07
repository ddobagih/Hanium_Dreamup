#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "rtklib.h"
#include "walksafe_ppk_engine.h"

static pthread_mutex_t ppk_mutex = PTHREAD_MUTEX_INITIALIZER;
#define WALKSAFE_MAX_SESSION_SECONDS (48.0 * 60.0 * 60.0)

struct walksafe_cancel_token {
    uint64_t generation;
    int cancelled;
};

static walksafe_cancel_token_t *active_cancel_token = NULL;

walksafe_cancel_token_t *walksafe_cancel_token_create(uint64_t generation) {
    if (generation == 0) return NULL;
    walksafe_cancel_token_t *token = calloc(1, sizeof(*token));
    if (token != NULL) token->generation = generation;
    return token;
}

int walksafe_cancel_token_matches(const walksafe_cancel_token_t *token, uint64_t generation) {
    return token != NULL && generation != 0 && token->generation == generation;
}

int walksafe_cancel_token_cancel(walksafe_cancel_token_t *token, uint64_t generation) {
    if (!walksafe_cancel_token_matches(token, generation)) return -1;
    __atomic_store_n(&token->cancelled, 1, __ATOMIC_RELEASE);
    return 0;
}

int walksafe_cancel_token_destroy(walksafe_cancel_token_t *token, uint64_t generation) {
    if (!walksafe_cancel_token_matches(token, generation) ||
        __atomic_load_n(&active_cancel_token, __ATOMIC_ACQUIRE) == token) return -1;
    free(token);
    return 0;
}

static int cancellation_requested(const walksafe_cancel_token_t *token) {
    return token != NULL && __atomic_load_n(&token->cancelled, __ATOMIC_ACQUIRE) != 0;
}

int showmsg(const char *format, ...) {
    (void) format;
    return cancellation_requested(__atomic_load_n(&active_cancel_token, __ATOMIC_ACQUIRE));
}

void settspan(gtime_t start, gtime_t end) {
    (void) start;
    (void) end;
}

void settime(gtime_t time) {
    (void) time;
}

static int event_output_path(const char *output_path, char event_path[MAXSTRPATH]) {
    static const char suffix[] = "_events.pos";
    const char *extension = strrchr(output_path, '.');
    const size_t prefix_length = extension == NULL
        ? strlen(output_path)
        : (size_t) (extension - output_path);
    if (prefix_length + sizeof(suffix) > MAXSTRPATH) return -1;
    memcpy(event_path, output_path, prefix_length);
    memcpy(event_path + prefix_length, suffix, sizeof(suffix));
    return 0;
}

int walksafe_rinex_observation_summary(const char *input_path, int summary[4]) {
    if (input_path == NULL || input_path[0] == '\0' || summary == NULL) return -1;

    obs_t observations = {0};
    nav_t navigation = {0};
    sta_t station = {0};
    gtime_t start = {0};
    gtime_t end = {0};
    int satellites[MAXSAT] = {0};

    pthread_mutex_lock(&ppk_mutex);
    const int read_result = readrnxt(
        input_path,
        1,
        start,
        end,
        0.0,
        "",
        &observations,
        &navigation,
        &station
    );
    if (read_result < 0 || observations.n <= 0) {
        freeobs(&observations);
        freenav(&navigation, 0xFF);
        pthread_mutex_unlock(&ppk_mutex);
        return -1;
    }

    summary[0] = sortobs(&observations);
    summary[1] = summary[2] = summary[3] = 0;
    for (int index = 0; index < observations.n; index++) {
        const obsd_t *observation = &observations.data[index];
        if (observation->sat >= 1 && observation->sat <= MAXSAT) {
            satellites[observation->sat - 1] = 1;
        }
        for (int frequency = 0; frequency < NFREQ + NEXOBS; frequency++) {
            if (observation->P[frequency] != 0.0) summary[2]++;
            if (observation->L[frequency] != 0.0) summary[3]++;
        }
    }
    for (int satellite = 0; satellite < MAXSAT; satellite++) {
        summary[1] += satellites[satellite];
    }

    freeobs(&observations);
    freenav(&navigation, 0xFF);
    pthread_mutex_unlock(&ppk_mutex);
    return summary[0] > 0 && summary[1] > 0 && summary[2] > 0 && summary[3] > 0 ? 0 : -1;
}

int walksafe_ppk_run(
        const char **input_paths,
        int input_count,
        int base_observation_count,
        const char *output_path,
        walksafe_cancel_token_t *cancel_token) {
    if (input_paths == NULL || input_count < 3 || base_observation_count < 1 ||
        base_observation_count > input_count - 2 || output_path == NULL || output_path[0] == '\0' ||
        cancel_token == NULL) {
        return -1;
    }

    prcopt_t processing = prcopt_default;
    solopt_t solution = solopt_default;
    filopt_t files = {0};
    gtime_t start = {0};
    gtime_t end = {0};
    obs_t rover_observations = {0};
    nav_t rover_navigation = {0};
    sta_t rover_station = {0};
    const int navigation_start = 1 + base_observation_count;
    const int post_input_count = 2 + input_count - navigation_start;
    const char **post_inputs = calloc((size_t) post_input_count, sizeof(char *));
    char staging_directory[MAXSTRPATH] = {0};
    char base_pattern[MAXSTRPATH] = {0};
    char event_path[MAXSTRPATH] = {0};
    char **base_links = NULL;
    int result = -1;
    int linked_bases = 0;

    if (post_inputs == NULL || event_output_path(output_path, event_path) != 0) {
        free(post_inputs);
        return -1;
    }
    for (int index = 0; index < input_count; index++) {
        if (strcmp(input_paths[index], output_path) == 0 ||
            strcmp(input_paths[index], event_path) == 0) {
            free(post_inputs);
            return -1;
        }
    }
    pthread_mutex_lock(&ppk_mutex);
    __atomic_store_n(&active_cancel_token, cancel_token, __ATOMIC_RELEASE);
    if (cancellation_requested(cancel_token)) {
        result = WALKSAFE_PPK_CANCELLED;
        goto cleanup;
    }

    if (readrnxt(input_paths[0], 1, start, end, 0.0, "", &rover_observations,
                 &rover_navigation, &rover_station) < 0 ||
        sortobs(&rover_observations) <= 0) {
        goto cleanup;
    }
    if (cancellation_requested(cancel_token)) {
        result = WALKSAFE_PPK_CANCELLED;
        goto cleanup;
    }
    start = rover_observations.data[0].time;
    end = rover_observations.data[rover_observations.n - 1].time;
    if (timediff(end, start) < 0.0 || timediff(end, start) > WALKSAFE_MAX_SESSION_SECONDS) {
        goto cleanup;
    }

    post_inputs[0] = input_paths[0];
    if (base_observation_count == 1) {
        post_inputs[1] = input_paths[1];
    } else {
        if (snprintf(staging_directory, sizeof(staging_directory), "%s.walksafe-inputs.XXXXXX", output_path) >=
            (int) sizeof(staging_directory) ||
            mkdtemp(staging_directory) == NULL ||
            snprintf(base_pattern, sizeof(base_pattern), "%s/base-*.obs", staging_directory) >=
                (int) sizeof(base_pattern)) {
            goto cleanup;
        }
        base_links = calloc((size_t) base_observation_count, sizeof(char *));
        if (base_links == NULL) goto cleanup;
        for (int index = 0; index < base_observation_count; index++) {
            base_links[index] = calloc(MAXSTRPATH, sizeof(char));
            if (base_links[index] == NULL ||
                snprintf(base_links[index], MAXSTRPATH, "%s/base-%03d.obs",
                         staging_directory, index) >= MAXSTRPATH) {
                goto cleanup;
            }
            if (strcmp(base_links[index], output_path) == 0 ||
                strcmp(base_links[index], event_path) == 0) goto cleanup;
            if (symlink(input_paths[index + 1], base_links[index]) != 0) goto cleanup;
            linked_bases++;
        }
        post_inputs[1] = base_pattern;
    }
    for (int index = navigation_start; index < input_count; index++) {
        post_inputs[index - navigation_start + 2] = input_paths[index];
    }

    processing.mode = PMODE_KINEMA;
    processing.soltype = SOLTYPE_COMBINED;
    processing.nf = 3;
    processing.navsys = SYS_GPS | SYS_GLO | SYS_GAL | SYS_QZS | SYS_CMP;
    processing.refpos = POSOPT_RINEX;
    processing.dynamics = 1;
    processing.intpref = 1;
    processing.outsingle = 0;

    solution.posf = SOLF_LLH;
    solution.times = TIMES_UTC;
    solution.timef = 1;
    solution.timeu = 3;
    solution.degf = 0;
    solution.outhead = 1;
    solution.outopt = 1;
    solution.outvel = 0;
    solution.sstat = 0;
    solution.trace = 0;
    snprintf(solution.prog, sizeof(solution.prog), "WalkSafe RTKLIB-EX %s", PATCH_LEVEL);

    if ((remove(output_path) != 0 && errno != ENOENT) ||
        (remove(event_path) != 0 && errno != ENOENT)) {
        result = -1;
        goto cleanup;
    }
    result = postpos(
        start,
        end,
        0.0,
        0.0,
        &processing,
        &solution,
        &files,
        post_inputs,
        post_input_count,
        output_path,
        "",
        ""
    );
    if (cancellation_requested(cancel_token)) result = WALKSAFE_PPK_CANCELLED;
    if (remove(event_path) != 0 && errno != ENOENT) result = -1;
cleanup:
    freeobs(&rover_observations);
    freenav(&rover_navigation, 0xFF);
    for (int index = 0; index < linked_bases; index++) unlink(base_links[index]);
    if (base_links != NULL) {
        for (int index = 0; index < base_observation_count; index++) free(base_links[index]);
    }
    free(base_links);
    if (staging_directory[0] != '\0') rmdir(staging_directory);
    free(post_inputs);
    if (result == WALKSAFE_PPK_CANCELLED) {
        remove(output_path);
        remove(event_path);
    }
    __atomic_store_n(&active_cancel_token, NULL, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&ppk_mutex);
    return result;
}

#ifdef WALKSAFE_TESTING
int walksafe_ppk_test_wait_for_inflight_cancel(walksafe_cancel_token_t *token) {
    if (token == NULL) return -1;
    const struct timespec pause = {0, 1000 * 1000};
    pthread_mutex_lock(&ppk_mutex);
    __atomic_store_n(&active_cancel_token, token, __ATOMIC_RELEASE);
    int result = -1;
    for (int attempt = 0; attempt < 10 * 1000; attempt++) {
        if (showmsg("test")) {
            result = WALKSAFE_PPK_CANCELLED;
            break;
        }
        nanosleep(&pause, NULL);
    }
    __atomic_store_n(&active_cancel_token, NULL, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&ppk_mutex);
    return result;
}
#endif
