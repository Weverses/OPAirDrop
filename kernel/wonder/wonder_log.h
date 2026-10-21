/* SPDX-License-Identifier: GPL-2.0 */
/*
 * Logging macro definitions for the Wonder driver. This provides a consistent
 * and descriptive format for all kernel log messages originating from this module.
 */

#ifndef __WONDER_LOG_H__
#define __WONDER_LOG_H__

#include <linux/printk.h>

#include "core.h"

/*
 * Adds the function name to the log prefix for better debugging context.
 * Example output: "[wonder]: Your message here"
 */
#ifndef LOG_MODULE_NAME
#define wonder_debug(fmt, ...) \
	pr_debug("[wonder] " fmt, ##__VA_ARGS__)

#define wonder_info(fmt, ...) \
	pr_info("[wonder] " fmt, ##__VA_ARGS__)

#define wonder_warn(fmt, ...) \
	pr_warn("[wonder] " fmt, ##__VA_ARGS__)

#define wonder_error(fmt, ...) \
	pr_err("[wonder] " fmt, ##__VA_ARGS__)
#else
#define wonder_debug(fmt, ...) \
	pr_debug("[wonder][" LOG_MODULE_NAME "] : " fmt, ##__VA_ARGS__)

#define wonder_info(fmt, ...) \
	pr_info("[wonder][" LOG_MODULE_NAME "] " fmt, ##__VA_ARGS__)

#define wonder_warn(fmt, ...) \
	pr_warn("[wonder][" LOG_MODULE_NAME "] " fmt, ##__VA_ARGS__)

#define wonder_error(fmt, ...) \
	pr_err("[wonder][" LOG_MODULE_NAME "] " fmt, ##__VA_ARGS__)
#endif /* LOG_MODULE_NAME */

#endif /* __WONDER_LOG_H__ */
