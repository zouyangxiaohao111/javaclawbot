package agent.tool.plan;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Random word slug generator for plan file IDs.
 * Corresponds to Open-ClaudeCode: src/utils/words.ts
 *
 * Generates slugs in the format "adjective-verb-noun" (e.g., "calm-brewing-tiger").
 */
public class WordSlugGenerator {

    private static final String[] ADJECTIVES = {
        "abundant", "ancient", "bright", "calm", "cheerful", "clever", "cozy", "curious",
        "dapper", "dazzling", "deep", "delightful", "eager", "elegant", "enchanted", "fancy",
        "fluffy", "gentle", "gleaming", "golden", "graceful", "happy", "hidden", "humble",
        "jolly", "joyful", "keen", "kind", "lively", "lovely", "lucky", "luminous",
        "magical", "majestic", "mellow", "merry", "mighty", "misty", "noble", "peaceful",
        "playful", "polished", "precious", "proud", "quiet", "quirky", "radiant", "rosy",
        "serene", "shiny", "silly", "sleepy", "smooth", "snazzy", "snug", "soft",
        "sparkling", "spicy", "splendid", "starry", "steady", "sunny", "swift", "tender",
        "tidy", "toasty", "tranquil", "twinkly", "valiant", "vast", "velvet", "vivid",
        "warm", "whimsical", "wild", "wise", "witty", "wondrous", "zany", "zesty", "zippy"
    };

    private static final String[] VERBS = {
        "baking", "beaming", "booping", "bouncing", "brewing", "bubbling", "chasing",
        "churning", "conjuring", "cooking", "crafting", "crunching", "cuddling", "dancing",
        "dazzling", "discovering", "doodling", "dreaming", "drifting", "enchanting",
        "exploring", "finding", "floating", "fluttering", "foraging", "forging", "gathering",
        "giggling", "gliding", "growing", "hatching", "herding", "hopping", "hugging",
        "humming", "imagining", "inventing", "jingling", "juggling", "jumping", "knitting",
        "launching", "leaping", "mapping", "mixing", "munching", "napping", "nibbling",
        "orbiting", "painting", "petting", "plotting", "pondering", "popping", "prancing",
        "purring", "puzzling", "questing", "roaming", "rolling", "seeking", "singing",
        "skipping", "snuggling", "soaring", "spinning", "splashing", "sprouting", "stirring",
        "strolling", "swimming", "swinging", "tickling", "tinkering", "toasting", "tumbling",
        "twirling", "wandering", "weaving", "whistling", "wiggling", "wishing", "wobbling",
        "wondering", "zooming"
    };

    private static final String[] NOUNS = {
        "alpaca", "aurora", "axolotl", "badger", "bear", "beaver", "bee", "bird",
        "blossom", "breeze", "brook", "bubble", "bunny", "canyon", "cascade", "cat",
        "chipmunk", "cloud", "clover", "comet", "coral", "cosmos", "creek", "crystal",
        "dawn", "deer", "dewdrop", "dolphin", "dove", "dragon", "dragonfly", "eagle",
        "eclipse", "elephant", "ember", "falcon", "feather", "firefly", "flame", "flamingo",
        "forest", "fox", "frog", "frost", "galaxy", "garden", "glacier", "glade",
        "grove", "hamster", "harbor", "hare", "hedgehog", "hippo", "horizon", "hummingbird",
        "island", "jellyfish", "kitten", "koala", "lagoon", "lake", "lantern", "lemur",
        "llama", "lobster", "lynx", "manatee", "meadow", "meerkat", "meteor", "mist",
        "moon", "moonbeam", "mountain", "narwhal", "nebula", "nova", "ocean", "octopus",
        "orbit", "otter", "owl", "panda", "parrot", "peacock", "pebble", "pelican",
        "penguin", "phoenix", "planet", "platypus", "pond", "puffin", "puppy", "quasar",
        "rabbit", "raccoon", "rain", "rainbow", "raven", "reef", "ripple", "river",
        "robin", "rocket", "salamander", "seahorse", "seal", "sky", "sloth", "snail",
        "snowflake", "spark", "sparrow", "sphinx", "squid", "squirrel", "star", "stardust",
        "starfish", "storm", "stream", "summit", "sun", "sunrise", "sunset", "swan",
        "thunder", "tide", "tiger", "toucan", "turtle", "twilight", "unicorn", "valley",
        "volcano", "walrus", "waterfall", "wave", "whale", "willow", "wind", "wolf",
        "wombat", "wren", "yeti", "zebra", "zephyr"
    };

    private WordSlugGenerator() {}

    public static String generateWordSlug() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String adj = ADJECTIVES[rng.nextInt(ADJECTIVES.length)];
        String verb = VERBS[rng.nextInt(VERBS.length)];
        String noun = NOUNS[rng.nextInt(NOUNS.length)];
        return adj + "-" + verb + "-" + noun;
    }
}
