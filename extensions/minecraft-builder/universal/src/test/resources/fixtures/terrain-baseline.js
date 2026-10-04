/* Independent ES5 terrain and path algorithms. Module: openallay_builder:terrain. */
'use strict';

exports.install = function (api, util) {
    var own = Object.prototype.hasOwnProperty;
    var air = {'minecraft:air': true, 'minecraft:cave_air': true, 'minecraft:void_air': true};
    var vegetation = {};
    var natural = {};
    var plantNames = ('short_grass grass tall_grass fern large_fern dead_bush bush firefly_bush ' +
        'seagrass tall_seagrass kelp kelp_plant vine glow_lichen hanging_roots mangrove_roots ' +
        'muddy_mangrove_roots bamboo bamboo_sapling sugar_cane cactus cactus_flower ' +
        'dandelion poppy blue_orchid allium azure_bluet red_tulip orange_tulip white_tulip ' +
        'pink_tulip oxeye_daisy cornflower lily_of_the_valley wither_rose sunflower lilac ' +
        'rose_bush peony torchflower pitcher_plant pink_petals wildflowers leaf_litter ' +
        'brown_mushroom red_mushroom brown_mushroom_block red_mushroom_block mushroom_stem ' +
        'crimson_fungus warped_fungus crimson_roots warped_roots nether_sprouts ' +
        'weeping_vines weeping_vines_plant twisting_vines twisting_vines_plant ' +
        'cave_vines cave_vines_plant spore_blossom small_dripleaf big_dripleaf big_dripleaf_stem ' +
        'sweet_berry_bush wheat carrots potatoes beetroots melon_stem attached_melon_stem ' +
        'pumpkin_stem attached_pumpkin_stem torchflower_crop pitcher_crop nether_wart cocoa ' +
        'lily_pad moss_carpet pale_moss_carpet pale_hanging_moss dry_grass tall_dry_grass short_dry_grass').split(' ');
    var fallbackGround = ('grass_block dirt coarse_dirt rooted_dirt podzol mycelium dirt_path ' +
        'farmland mud packed_mud clay sand red_sand gravel stone granite diorite andesite ' +
        'deepslate tuff calcite bedrock netherrack soul_sand soul_soil end_stone ' +
        'snow_block snow ice packed_ice blue_ice moss_block pale_moss_block ' +
        'terracotta dripstone_block crimson_nylium warped_nylium basalt blackstone').split(' ');
    var i;
    function qualified(id) {
        if (typeof id !== 'string' || !id.length) { throw new Error('Block ID must be a nonempty string'); }
        return id.indexOf(':') < 0 ? 'minecraft:' + id : id;
    }
    for (i = 0; i < plantNames.length; i++) { vegetation['minecraft:' + plantNames[i]] = true; }
    var groundList = fallbackGround.concat(api.NATURAL_GROUND || []);
    for (i = 0; i < groundList.length; i++) { natural[qualified(groundList[i])] = true; }
    function isAir(block) { return air[block.id] === true; }
    function isLiquid(block) {
        return block.id === 'minecraft:water' || block.id === 'minecraft:lava' ||
            block.id === 'minecraft:bubble_column' ||
            block.properties.waterlogged === 'true' || block.properties.waterlogged === true;
    }
    function isVegetation(block) {
        return vegetation[block.id] === true ||
            (block.id.indexOf('minecraft:') === 0 && block.id.indexOf('minecraft:stripped_') !== 0 &&
                /_(log|leaves|sapling)$/.test(block.id) &&
                block.properties.persistent !== 'true' && block.properties.persistent !== true);
    }
    function optionBoolean(opts, name, fallback) {
        if (opts[name] === undefined) { return fallback; }
        if (typeof opts[name] !== 'boolean') { throw new Error(name + ' must be a boolean'); }
        return opts[name];
    }
    function nonnegative(value, name) {
        value = util.integer(value, name);
        if (value < 0) { throw new Error(name + ' must not be negative'); }
        return value;
    }
    function rect(x1, z1, x2, z2) {
        x1 = util.integer(x1, 'x1'); z1 = util.integer(z1, 'z1');
        x2 = util.integer(x2, 'x2'); z2 = util.integer(z2, 'z2');
        return {x1: Math.min(x1, x2), z1: Math.min(z1, z2), x2: Math.max(x1, x2), z2: Math.max(z1, z2)};
    }
    function context() {
        var c = api.context();
        var lo = util.integer(c.minY, 'context.minY'), hi = util.integer(c.maxY, 'context.maxY');
        if (lo >= hi) { throw new Error('World height is empty'); }
        return {minY: lo, maxY: hi};
    }
    function heightRange(opts, c) {
        var lo = opts.minY === undefined ? c.minY : util.integer(opts.minY, 'minY');
        var hi = opts.maxY === undefined ? c.maxY : util.integer(opts.maxY, 'maxY');
        if (lo < c.minY || hi > c.maxY || lo >= hi) { throw new Error('Scan height must be inside the world height; maxY is exclusive'); }
        return {minY: lo, maxY: hi};
    }
    function groundSet(opts) {
        var extra = {}, list = opts.groundBlocks, n;
        if (list !== undefined) {
            if (!Array.isArray(list)) { throw new Error('groundBlocks must be an array of block IDs'); }
            for (n = 0; n < list.length; n++) { extra[qualified(list[n])] = true; }
        }
        return function (block) {
            return !isAir(block) && !isLiquid(block) && !isVegetation(block) &&
                (natural[block.id] === true || extra[block.id] === true);
        };
    }
    function reader(c) {
        var cache = Object.create(null);
        return function (x, y, z) {
            if (y < c.minY || y >= c.maxY) { throw new Error('Read outside world height at ' + x + ',' + y + ',' + z); }
            var key = x + ',' + y + ',' + z, block;
            if (own.call(cache, key)) { return cache[key]; }
            block = api.get_block_full(x, y, z);
            if (!block || typeof block.id !== 'string' || !block.id.length) {
                throw new Error('Unknown or unloaded block at ' + key);
            }
            if (!block.properties) { block = {id: block.id, properties: {}}; }
            cache[key] = block;
            return block;
        };
    }
    function column(x, z, range, read, accept) {
        var y, block;
        for (y = range.maxY - 1; y >= range.minY; y--) {
            block = read(x, y, z);
            if (accept(block)) { return {x: x, z: z, y: y, block: block.id, properties: block.properties}; }
        }
        return {x: x, z: z, y: null, block: null, properties: null};
    }
    function scan(x1, z1, x2, z2, opts, groundOnly) {
        opts = util.options(opts);
        var b = rect(x1, z1, x2, z2), c = context(), range = heightRange(opts, c);
        var read = reader(c), accept = groundOnly ? groundSet(opts) : function (block) { return !isAir(block); };
        var result = [], x, z;
        for (x = b.x1; x <= b.x2; x++) {
            for (z = b.z1; z <= b.z2; z++) { result.push(column(x, z, range, read, accept)); }
        }
        return result;
    }
    api.scan_terrain = function (x1, z1, x2, z2, opts) { return scan(x1, z1, x2, z2, opts, false); };
    api.scan_ground = function (x1, z1, x2, z2, opts) { return scan(x1, z1, x2, z2, opts, true); };
    api.get_terrain_bounds = function (columns) {
        if (!Array.isArray(columns)) { throw new Error('get_terrain_bounds expects a scan result array'); }
        var out = {x1: null, z1: null, x2: null, z2: null, minY: null, maxY: null, count: columns.length, found: 0, missing: 0};
        var n, col, x, z, y;
        for (n = 0; n < columns.length; n++) {
            col = columns[n]; x = util.integer(col.x, 'column.x'); z = util.integer(col.z, 'column.z');
            out.x1 = out.x1 === null ? x : Math.min(out.x1, x);
            out.x2 = out.x2 === null ? x : Math.max(out.x2, x);
            out.z1 = out.z1 === null ? z : Math.min(out.z1, z);
            out.z2 = out.z2 === null ? z : Math.max(out.z2, z);
            if (col.y === null) { out.missing++; continue; }
            y = util.integer(col.y, 'column.y'); out.found++;
            out.minY = out.minY === null ? y : Math.min(out.minY, y);
            out.maxY = out.maxY === null ? y : Math.max(out.maxY, y);
        }
        return out;
    };
    function normalizedState(value, fallback) {
        return util.state(value === undefined ? fallback : value);
    }
    function writer(read) {
        var edits = [], seen = Object.create(null);
        return {
            add: function (x, y, z, state) {
                var key = x + ',' + y + ',' + z;
                read(x, y, z); // Complete all observed-cell validation before any write.
                if (!own.call(seen, key)) { seen[key] = edits.length; edits.push({x: x, y: y, z: z, state: state}); }
                else { edits[seen[key]].state = state; }
            },
            apply: function () {
                var n, e;
                for (n = 0; n < edits.length; n++) {
                    e = edits[n]; api.place_block(e.x, e.y, e.z, e.state);
                }
            }
        };
    }
    api.flatten_area = function (x1, z1, x2, z2, targetY, opts) {
        opts = util.options(opts);
        var before = util.count(), b = rect(x1, z1, x2, z2), c = context(), range = heightRange(opts, c);
        targetY = util.integer(targetY, 'targetY');
        if (targetY < c.minY || targetY >= c.maxY) { throw new Error('targetY is outside world height'); }
        var surface = normalizedState(opts.surface, 'minecraft:grass_block');
        var underground = normalizedState(opts.underground, 'minecraft:dirt');
        var empty = normalizedState('minecraft:air');
        var depth = opts.depth === undefined ? 3 : nonnegative(opts.depth, 'depth');
        var radius = opts.blendRadius === undefined ? 0 : nonnegative(opts.blendRadius, 'blendRadius');
        var clear = opts.clearAbove === undefined ? true : opts.clearAbove;
        if (typeof clear !== 'boolean') { clear = nonnegative(clear, 'clearAbove'); }
        var rand = util.rng(opts.seed === undefined ? util.seed : opts.seed);
        util.integer(b.x1 - radius, 'expanded x1'); util.integer(b.x2 + radius, 'expanded x2');
        util.integer(b.z1 - radius, 'expanded z1'); util.integer(b.z2 + radius, 'expanded z2');
        var read = reader(c), edits = writer(read), accept = groundSet(opts);
        var x, z, y, distance, existing, desired, exact, low, changed = 0;
        for (x = b.x1 - radius; x <= b.x2 + radius; x++) {
            for (z = b.z1 - radius; z <= b.z2 + radius; z++) {
                distance = Math.max(b.x1 - x, x - b.x2, b.z1 - z, z - b.z2, 0);
                desired = targetY;
                if (distance > 0) {
                    existing = column(x, z, range, read, accept);
                    if (existing.y === null) { continue; }
                    exact = existing.y + (targetY - existing.y) * (1 - distance / (radius + 1));
                    low = Math.floor(exact);
                    desired = low + (rand() < exact - low ? 1 : 0);
                }
                for (y = Math.max(c.minY, desired - depth); y < desired; y++) { edits.add(x, y, z, underground); }
                edits.add(x, desired, z, surface);
                if (clear) {
                    for (y = desired + 1; y < (clear === true ? c.maxY : Math.min(c.maxY, desired + 1 + clear)); y++) {
                        if (!isAir(read(x, y, z))) { edits.add(x, y, z, empty); }
                    }
                }
                changed++;
            }
        }
        edits.apply();
        var result = util.summary('flatten_area', before);
        result.columns = changed; result.targetY = targetY; result.blendRadius = radius;
        return result;
    };
    api.clear_vegetation = function (x1, y1, z1, x2, y2, z2, opts) {
        opts = util.options(opts);
        var before = util.count(), b = rect(x1, z1, x2, z2), c = context();
        y1 = util.integer(y1, 'y1'); y2 = util.integer(y2, 'y2');
        var low = Math.min(y1, y2), high = Math.max(y1, y2), mode = opts.mode === undefined ? 'vegetation' : opts.mode;
        if (low < c.minY || high >= c.maxY) { throw new Error('Clear bounds are outside world height'); }
        if (mode !== 'vegetation' && mode !== 'all') { throw new Error('mode must be vegetation or all'); }
        var read = reader(c), edits = writer(read), empty = normalizedState('minecraft:air'), x, y, z, block;
        for (x = b.x1; x <= b.x2; x++) {
            for (z = b.z1; z <= b.z2; z++) {
                for (y = low; y <= high; y++) {
                    block = read(x, y, z);
                    if (!isAir(block) && (mode === 'all' || isVegetation(block))) { edits.add(x, y, z, empty); }
                }
            }
        }
        edits.apply();
        return util.summary('clear_vegetation', before);
    };
    function position(point, label) {
        if (!point || typeof point !== 'object') { throw new Error(label + ' must be {x,z}'); }
        return {x: util.integer(point.x, label + '.x'), z: util.integer(point.z, label + '.z')};
    }
    function palette(opts) {
        var blocks = opts.blocks === undefined ? ['minecraft:dirt_path'] : opts.blocks, result = [], n;
        if (!Array.isArray(blocks)) { blocks = [blocks]; }
        if (!blocks.length) { throw new Error('blocks must not be empty'); }
        for (n = 0; n < blocks.length; n++) { result.push(normalizedState(blocks[n])); }
        return result;
    }
    function pathEnvironment(start, end, opts, smart) {
        var c = context(), range = heightRange(opts, c), read = reader(c), ground = groundSet(opts);
        var width = opts.width === undefined ? 1 : util.positive(opts.width, 'width');
        width = util.integer(width, 'width');
        var lower = -Math.floor((width - 1) / 2), upper = lower + width - 1;
        var step = opts.maxStep === undefined ? 1 : nonnegative(opts.maxStep, 'maxStep');
        var clearance = opts.clearance === undefined ? 2 : nonnegative(opts.clearance, 'clearance');
        var penalty = opts.heightPenalty === undefined ? 1 : opts.heightPenalty;
        if (typeof penalty !== 'number' || !isFinite(penalty) || penalty < 0) { throw new Error('heightPenalty must be a finite nonnegative number'); }
        var fixed = null;
        if (opts.y !== undefined) {
            if (smart) { throw new Error('build_smart_path follows terrain; use build_path for a fixed y'); }
            fixed = util.integer(opts.y, 'y');
            if (fixed < c.minY || fixed >= c.maxY) { throw new Error('y is outside world height'); }
        }
        var bounds = null, supplied = opts.bounds;
        if (supplied !== undefined) {
            if (!supplied || typeof supplied !== 'object') { throw new Error('bounds must be {x1,z1,x2,z2}'); }
            bounds = rect(supplied.x1, supplied.z1, supplied.x2, supplied.z2);
        } else if (smart) {
            bounds = rect(Math.min(start.x, end.x) + lower, Math.min(start.z, end.z) + lower,
                Math.max(start.x, end.x) + upper, Math.max(start.z, end.z) + upper);
        }
        var columns = Object.create(null), nodes = Object.create(null), materials = palette(opts);
        function groundColumn(x, z) {
            var key = x + ',' + z;
            if (!own.call(columns, key)) {
                columns[key] = fixed === null ? column(x, z, range, read, ground) : {x: x, z: z, y: fixed};
            }
            return columns[key];
        }
        function node(x, z) {
            var key = x + ',' + z, dx, dz, col, cell, y, min = null, max = null, cells = [], centerY = null;
            if (own.call(nodes, key)) { return nodes[key]; }
            util.integer(x + lower, 'footprint x1'); util.integer(x + upper, 'footprint x2');
            util.integer(z + lower, 'footprint z1'); util.integer(z + upper, 'footprint z2');
            nodes[key] = null;
            if (bounds && (x + lower < bounds.x1 || x + upper > bounds.x2 || z + lower < bounds.z1 || z + upper > bounds.z2)) { return null; }
            for (dx = lower; dx <= upper; dx++) {
                for (dz = lower; dz <= upper; dz++) {
                    col = groundColumn(x + dx, z + dz);
                    if (col.y === null || col.y + clearance >= c.maxY) { return null; }
                    cell = read(col.x, col.y, col.z);
                    if (isLiquid(cell) || (fixed === null && !ground(cell))) { return null; }
                    for (y = col.y + 1; y <= col.y + clearance; y++) {
                        cell = read(col.x, y, col.z);
                        if (isLiquid(cell) || (!isAir(cell) && !isVegetation(cell))) { return null; }
                    }
                    if (dx === 0 && dz === 0) { centerY = col.y; }
                    min = min === null ? col.y : Math.min(min, col.y);
                    max = max === null ? col.y : Math.max(max, col.y);
                    cells.push(col);
                }
            }
            if (max - min > step) { return null; }
            nodes[key] = {x: x, z: z, y: centerY, cells: cells};
            return nodes[key];
        }
        function cardinalAllowed(a, b) {
            var n;
            if (!a || !b) { return false; }
            for (n = 0; n < a.cells.length; n++) {
                if (Math.abs(a.cells[n].y - b.cells[n].y) > step) { return false; }
            }
            return true;
        }
        function moveAllowed(a, b) {
            if (!cardinalAllowed(a, b)) { return false; }
            if (a.x !== b.x && a.z !== b.z) {
                var sideX = node(b.x, a.z), sideZ = node(a.x, b.z);
                return cardinalAllowed(a, sideX) && cardinalAllowed(sideX, b) &&
                    cardinalAllowed(a, sideZ) && cardinalAllowed(sideZ, b);
            }
            return true;
        }
        return {
            node: node, allowed: moveAllowed, read: read, clearance: clearance,
            materials: materials, width: width, penalty: penalty,
            diagonal: optionBoolean(opts, 'diagonal', true),
            random: util.rng(opts.seed === undefined ? util.seed : opts.seed)
        };
    }
    function result(label, before, status, path, cost, reason, width, visited, columns) {
        var out = util.summary(label, before);
        out.status = status; out.path = path; out.cost = cost; out.reason = reason;
        out.width = width; out.visited = visited; out.columns = columns;
        return out;
    }
    function noRoute(label, before, env, reason, visited) {
        return result(label, before, 'no_route', [], null, reason, env.width, visited, 0);
    }
    function pave(label, before, env, route, cost, visited) {
        var edits = writer(env.read), done = Object.create(null), path = [], empty = normalizedState('minecraft:air');
        var n, m, cell, key, y, block, count = 0;
        for (n = 0; n < route.length; n++) {
            path.push({x: route[n].x, y: route[n].y, z: route[n].z});
            for (m = 0; m < route[n].cells.length; m++) {
                cell = route[n].cells[m]; key = cell.x + ',' + cell.z;
                if (own.call(done, key)) { continue; }
                done[key] = true; count++;
                edits.add(cell.x, cell.y, cell.z, env.materials[Math.floor(env.random() * env.materials.length)]);
                for (y = cell.y + 1; y <= cell.y + env.clearance; y++) {
                    block = env.read(cell.x, y, cell.z);
                    if (isVegetation(block)) { edits.add(cell.x, y, cell.z, empty); }
                }
            }
        }
        edits.apply();
        return result(label, before, 'built', path, cost, null, env.width, visited, count);
    }
    function moveCost(a, b, penalty) {
        return (a.x !== b.x && a.z !== b.z ? Math.SQRT2 : 1) + penalty * Math.abs(b.y - a.y);
    }
    api.build_path = function (start, end, opts) {
        if (typeof start === 'number') {
            var args = arguments, positionalOptions = util.options(args[5]), copied = {}, name;
            for (name in positionalOptions) { if (own.call(positionalOptions, name)) { copied[name] = positionalOptions[name]; } }
            copied.y = args[4];
            start = {x: args[0], z: args[1]}; end = {x: args[2], z: args[3]}; opts = copied;
        }
        opts = util.options(opts); start = position(start, 'start'); end = position(end, 'end');
        var before = util.count(), env = pathEnvironment(start, end, opts, false), route = [], cost = 0;
        var x = start.x, z = start.z, dx = Math.abs(end.x - x), dz = Math.abs(end.z - z);
        var sx = x < end.x ? 1 : -1, sz = z < end.z ? 1 : -1, error = dx - dz, twice, next, previous;
        while (true) {
            next = env.node(x, z);
            if (!next || (previous && !env.allowed(previous, next))) { return noRoute('build_path', before, env, 'blocked_path', route.length); }
            route.push(next);
            if (previous) { cost += moveCost(previous, next, env.penalty); }
            if (x === end.x && z === end.z) { break; }
            previous = next; twice = 2 * error;
            if (twice > -dz) { error -= dz; x += sx; }
            if (twice < dx) { error += dx; z += sz; }
        }
        return pave('build_path', before, env, route, cost, route.length);
    };
    function heap() {
        var data = [], serial = 0;
        function less(a, b) { return a.f < b.f || (a.f === b.f && (a.h < b.h || (a.h === b.h && a.order < b.order))); }
        return {
            size: function () { return data.length; },
            push: function (item) {
                item.order = serial++;
                var n = data.length, parent;
                data.push(item);
                while (n > 0) {
                    parent = Math.floor((n - 1) / 2);
                    if (!less(item, data[parent])) { break; }
                    data[n] = data[parent]; n = parent;
                }
                data[n] = item;
            },
            pop: function () {
                var first = data[0], last = data.pop(), n = 0, child;
                if (data.length) {
                    while (2 * n + 1 < data.length) {
                        child = 2 * n + 1;
                        if (child + 1 < data.length && less(data[child + 1], data[child])) { child++; }
                        if (!less(data[child], last)) { break; }
                        data[n] = data[child]; n = child;
                    }
                    data[n] = last;
                }
                return first;
            }
        };
    }
    api.build_smart_path = function (start, end, opts) {
        opts = util.options(opts); start = position(start, 'start'); end = position(end, 'end');
        var before = util.count(), env = pathEnvironment(start, end, opts, true), first = env.node(start.x, start.z);
        if (!first) { return noRoute('build_smart_path', before, env, 'blocked_start', 0); }
        var last = env.node(end.x, end.z);
        if (!last) { return noRoute('build_smart_path', before, env, 'blocked_end', 0); }
        var directions = [[1, 0], [0, 1], [-1, 0], [0, -1]];
        if (env.diagonal) { directions = directions.concat([[1, 1], [-1, 1], [-1, -1], [1, -1]]); }
        function heuristic(x, z) {
            var dx = Math.abs(end.x - x), dz = Math.abs(end.z - z), low = Math.min(dx, dz);
            return env.diagonal ? Math.max(dx, dz) + (Math.SQRT2 - 1) * low : dx + dz;
        }
        function key(node) { return node.x + ',' + node.z; }
        var open = heap(), scores = Object.create(null), parents = Object.create(null), visited = 0;
        var firstKey = key(first), current, currentKey, next, nextKey, g, h, n, route, cursor;
        scores[firstKey] = 0;
        h = heuristic(first.x, first.z); open.push({node: first, g: 0, h: h, f: h});
        while (open.size()) {
            current = open.pop(); currentKey = key(current.node);
            if (current.g !== scores[currentKey]) { continue; }
            visited++;
            if (current.node.x === end.x && current.node.z === end.z) {
                route = [current.node]; cursor = currentKey;
                while (own.call(parents, cursor)) { next = parents[cursor]; route.push(next); cursor = key(next); }
                route.reverse();
                return pave('build_smart_path', before, env, route, current.g, visited);
            }
            for (n = 0; n < directions.length; n++) {
                next = env.node(current.node.x + directions[n][0], current.node.z + directions[n][1]);
                if (!next || !env.allowed(current.node, next)) { continue; }
                nextKey = key(next); g = current.g + moveCost(current.node, next, env.penalty);
                if (own.call(scores, nextKey) && g >= scores[nextKey]) { continue; }
                scores[nextKey] = g; parents[nextKey] = current.node;
                h = heuristic(next.x, next.z); open.push({node: next, g: g, h: h, f: g + h});
            }
        }
        return noRoute('build_smart_path', before, env, 'no_route', visited);
    };
};
