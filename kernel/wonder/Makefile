obj-m += wonder.o
wonder-y := main.o mac80211.o wondertap.o nl80211_ven_cmd.o mac80211_txs.o ssr.o band_config.o

ifneq ($(CONFIG_DEBUG_FS),)
wonder-y += debugfs.o
endif

all:
	make -C /lib/modules/$(shell uname -r)/build M=$(PWD) modules

clean:
	make -C /lib/modules/$(shell uname -r)/build M=$(PWD) clean