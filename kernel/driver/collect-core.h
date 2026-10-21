/* Shared by the kernel experiment and host sanitizer tests. */
static inline unsigned long long
opairshare_collect_remaining(unsigned long long age_ns, unsigned int wait_us,
                       unsigned int have, unsigned int goal, int bulk,
                       int blocker, int accepting)
{
    unsigned long long limit;
    if (!accepting || !bulk || blocker || !wait_us || have >= goal)
        return 0;
    if (wait_us > 2000)
        wait_us = 2000;
    limit = (unsigned long long)wait_us * 1000;
    return age_ns >= limit ? 0 : limit - age_ns;
}
