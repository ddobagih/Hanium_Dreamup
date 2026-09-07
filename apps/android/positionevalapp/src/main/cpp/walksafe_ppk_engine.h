#ifndef WALKSAFE_PPK_ENGINE_H
#define WALKSAFE_PPK_ENGINE_H

#include <stdint.h>

typedef struct walksafe_cancel_token walksafe_cancel_token_t;
#define WALKSAFE_PPK_CANCELLED (-2)

walksafe_cancel_token_t *walksafe_cancel_token_create(uint64_t generation);
int walksafe_cancel_token_matches(const walksafe_cancel_token_t *token, uint64_t generation);
int walksafe_cancel_token_cancel(walksafe_cancel_token_t *token, uint64_t generation);
int walksafe_cancel_token_destroy(walksafe_cancel_token_t *token, uint64_t generation);
int walksafe_ppk_run(
    const char **input_paths,
    int input_count,
    int base_observation_count,
    const char *output_path,
    walksafe_cancel_token_t *cancel_token
);
int walksafe_rinex_observation_summary(const char *input_path, int summary[4]);

#ifdef WALKSAFE_TESTING
int walksafe_ppk_test_wait_for_inflight_cancel(walksafe_cancel_token_t *token);
#endif

#endif
