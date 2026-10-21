// SPDX-License-Identifier: GPL-2.0
/*
 * Google Wonder WiFi Virtual Soft-MAC Driver
 *
 * Debugfs implementation for the Wonder driver.
 */
#define LOG_MODULE_NAME "debugfs"

#include <linux/debugfs.h>
#include <linux/err.h>
#include <linux/netdevice.h>
#include <linux/uaccess.h>

#include "core.h"
#include "wonder_log.h"
#include "mac80211.h"

#ifdef CONFIG_DEBUG_FS
static struct dentry *wonder_debugfs_root;

static ssize_t wonder_force_stop_tx_write(struct file *file,
					  const char __user *user_buf,
					  size_t count, loff_t *ppos)
{
	struct wonder_data *wonder = file->private_data;
	bool stop;
	int ret;

	ret = kstrtobool_from_user(user_buf, count, &stop);
	if (ret)
		return ret;

	if (!wonder || !wonder->vdev) {
		wonder_error("vdev not available\n");
		return -ENODEV;
	}

	if (stop) {
		wonder_info("Forcing TX queue stop on %s\n", wonder->vdev->name);
		if (0)
			netif_stop_queue(wonder->vdev);
		else
			wonder->tx_stop = true;
	} else {
		wonder_info("Forcing TX queue wake on %s\n", wonder->vdev->name);
		if (0)
			netif_wake_queue(wonder->vdev);
		else
			wonder->tx_stop = false;
	}

	return count;
}

static const struct file_operations fops_force_stop_tx = {
	.write = wonder_force_stop_tx_write,
	.open = simple_open,
	.owner = THIS_MODULE,
};

int wonder_debugfs_init(void *wonder)
{
	wonder_debugfs_root = debugfs_create_dir("wonder", NULL);
	if (!wonder_debugfs_root)
		return -ENODEV;

	debugfs_create_file("force_stop_tx", 0200, wonder_debugfs_root,
			    wonder, &fops_force_stop_tx);
	return 0;
}

void wonder_debugfs_exit(void)
{
	debugfs_remove_recursive(wonder_debugfs_root);
}
#else /* !CONFIG_DEBUG_FS */
int wonder_debugfs_init(void *wonder)
{
	return 0;
}

void wonder_debugfs_exit(void)
{
}
#endif /* CONFIG_DEBUG_FS */
