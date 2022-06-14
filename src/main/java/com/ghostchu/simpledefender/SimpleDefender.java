package com.ghostchu.simpledefender;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.maxmind.db.CHMCache;
import com.maxmind.db.Metadata;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ClientConnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SimpleDefender extends Plugin implements Listener {
    private DatabaseReader reader;
    private final Cache<InetAddress, AtomicInteger> limit = CacheBuilder.newBuilder()
            .expireAfterWrite(20, TimeUnit.MINUTES)
            .build();
    private final AtomicLong count = new AtomicLong(0);

    @Override
    public void onEnable() {
        // Plugin startup logic
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File file = new File(getDataFolder(), "GeoLite2-Country.mmdb");
        if (!file.exists()) {
            try {
                Files.copy(getResourceAsStream("GeoLite2-Country.mmdb"), file.toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        try {
            reader = new DatabaseReader.Builder(file).withCache(new CHMCache()).build();
            getProxy().getPluginManager().registerListener(this, this);
            getLogger().info("启动成功");
            Metadata metadata = reader.getMetadata();
            getLogger().info("IP 数据库版本：" + metadata.getBinaryFormatMajorVersion() + "." + metadata.getBinaryFormatMinorVersion());
            getLogger().info("IP 信息版本：" + metadata.getIpVersion());
            getLogger().info("IP 数据库描述：" + metadata.getDescription());
            getLogger().info("IP 数据库更新日期：" + metadata.getBuildDate());
            getLogger().info("IP 数据库类型：" + metadata.getDatabaseType());
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        getProxy().getScheduler().schedule(this, () -> getLogger().info("SimpleDefender 屏蔽名单中目前包含 " + limit.size() + " 个 IP。自 BungeeCord 启动以来，SimpleDefender 已拦截 " + count.get() + " 次攻击请求。"), 300, 300, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        try {
            if (reader != null)
                reader.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            reader = null;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConnect(ClientConnectEvent event) {
        if (!(event.getSocketAddress() instanceof InetSocketAddress))
            return;
        InetSocketAddress address = (InetSocketAddress) event.getSocketAddress();
        if (address.getAddress().isAnyLocalAddress())
            return;
        for (ProxiedPlayer player : getProxy().getPlayers()) {
            if (player.getSocketAddress() instanceof InetSocketAddress) {
                InetSocketAddress playerAddress = (InetSocketAddress) player.getSocketAddress();
                if (playerAddress.getAddress().equals(address.getAddress())) {
                    return;
                }
            }
        }
        try {
            Optional<CountryResponse> countryOptional = reader.tryCountry(address.getAddress());
            if (countryOptional.isPresent()) {
                CountryResponse countryResponse = countryOptional.get();
                Country country = countryResponse.getCountry();
                if (country.getIsoCode().equalsIgnoreCase("CN") || country.getIsoCode().equalsIgnoreCase("HK") || country.getIsoCode().equalsIgnoreCase("TW")) {
                    int counter = limit.get(address.getAddress(), () -> new AtomicInteger(0)).incrementAndGet();
                    if (counter >= 30) {
                        count.incrementAndGet();
                        event.setCancelled(true);
                        if (counter == 30) {
                            getLogger().info("已屏蔽 " + address + ": 短时间内大量请求");
                        }
                    }
                    return;
                }
                count.incrementAndGet();
                event.setCancelled(true);
                if (limit.getIfPresent(address.getAddress()) == null) {
                    limit.put(address.getAddress(), new AtomicInteger(Integer.MAX_VALUE - 1));
                    getLogger().info("已屏蔽 " + address + ": 海外 IP 地址: " + country.getIsoCode());
                }
            }
        } catch (IOException | GeoIp2Exception e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
