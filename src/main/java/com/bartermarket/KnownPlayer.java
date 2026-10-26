package com.bartermarket;

import java.util.UUID;

public record KnownPlayer(UUID uuid, String name, long lastSeen) {
}
