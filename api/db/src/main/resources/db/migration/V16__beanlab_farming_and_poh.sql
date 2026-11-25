-- BeanLab: farming patch state per character (content/other/beanlab-farming).
--
-- The client shows a patch through a region-shared "farming_transmit" varbit, and the cache has no
-- per-patch storage varps, so each patch the player has touched is a row here. `patch` is the
-- packed coordinate of the patch loc. Growth is computed from `planted_at` (epoch seconds), so a
-- row only changes when the player acts on the patch.
CREATE TABLE beanlab_farming_patches (
    character_id INTEGER NOT NULL,
    patch INTEGER NOT NULL,
    crop TEXT NOT NULL DEFAULT '',
    weeds INTEGER NOT NULL DEFAULT 0 CHECK (weeds BETWEEN 0 AND 3),
    planted_at INTEGER NOT NULL DEFAULT 0,
    compost INTEGER NOT NULL DEFAULT 0 CHECK (compost BETWEEN 0 AND 3),
    lives INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (character_id, patch),
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE
);

-- BeanLab: furniture built in each character's player-owned house
-- (content/other/beanlab-construction). `room` and `hotspot` name a hotspot group in
-- ConstructionData; `furniture` is the index of the built option in that group.
CREATE TABLE beanlab_poh_furniture (
    character_id INTEGER NOT NULL,
    room TEXT NOT NULL,
    hotspot TEXT NOT NULL,
    furniture INTEGER NOT NULL,
    PRIMARY KEY (character_id, room, hotspot),
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE
);
