export type TeamsEmojiCategoryId = 'popular' | 'smileys' | 'gestures' | 'feelings' | 'activities';

export interface TeamsEmojiDefinition {
  emoji: string;
  name: string;
  keywords: readonly string[];
}

export interface TeamsEmojiCategory {
  id: TeamsEmojiCategoryId;
  labelKey: string;
  emoji: readonly TeamsEmojiDefinition[];
}

function emojiFromHex(hex: string): string {
  return String.fromCodePoint(Number.parseInt(hex, 16));
}

function definition(hex: string, name: string, keywords: readonly string[] = []): TeamsEmojiDefinition {
  return { emoji: emojiFromHex(hex), name, keywords: [name, ...keywords] };
}

const EMOJI = {
  like: definition('1f44d', 'Like', ['thumbs up', 'yes']),
  heart: definition('2764', 'Heart', ['love']),
  laugh: definition('1f606', 'Laugh', ['happy', 'grin']),
  surprised: definition('1f62e', 'Surprised', ['wow']),
  sad: definition('1f622', 'Sad', ['cry']),
  angry: definition('1f621', 'Angry', ['mad']),
  tears: definition('1f602', 'Tears of joy', ['laugh', 'funny']),
  fire: definition('1f525', 'Fire', ['hot']),
  party: definition('1f389', 'Party popper', ['celebrate']),
  clap: definition('1f44f', 'Clap', ['applause']),
  handshake: definition('1f91d', 'Handshake', ['agreement']),
  point: definition('1f440', 'Eyes', ['look']),
  rocket: definition('1f680', 'Rocket', ['launch']),
  star: definition('2b50', 'Star', ['favorite']),
  hundred: definition('1f4af', 'Hundred points', ['perfect']),
  check: definition('2705', 'Check mark', ['done']),
  sparkles: definition('2728', 'Sparkles', ['new']),
  pray: definition('1f64f', 'Folded hands', ['thanks']),
  muscle: definition('1f4aa', 'Flexed biceps', ['strong']),
};

export const TEAMS_QUICK_REACTIONS = [EMOJI.like, EMOJI.heart, EMOJI.laugh, EMOJI.surprised, EMOJI.sad, EMOJI.angry] as const;

const popular = [...TEAMS_QUICK_REACTIONS, EMOJI.tears, EMOJI.fire, EMOJI.party, EMOJI.clap, EMOJI.handshake, EMOJI.point, EMOJI.rocket, EMOJI.star, EMOJI.hundred, EMOJI.check, EMOJI.sparkles, EMOJI.pray, EMOJI.muscle];

const smileys = [
  ['1f600', 'Grinning face'], ['1f607', 'Smiling face with halo'], ['1f609', 'Winking face'], ['1f60b', 'Yum'], ['1f60c', 'Relieved face'], ['1f60d', 'Heart eyes'], ['1f60e', 'Cool face'], ['1f60f', 'Smirking face'], ['1f610', 'Neutral face'], ['1f611', 'Expressionless face'], ['1f612', 'Unamused face'], ['1f61b', 'Face with tongue'], ['1f61c', 'Winking face with tongue'], ['1f620', 'Angry face'], ['1f624', 'Face with steam'], ['1f62c', 'Grimacing face'], ['1f62d', 'Loudly crying face'], ['1f631', 'Face screaming in fear'], ['1f634', 'Sleeping face'], ['1f635', 'Dizzy face'], ['1f636', 'Face without mouth'], ['1f637', 'Face with medical mask'], ['1f642', 'Slightly smiling face'], ['1f644', 'Face with rolling eyes'], ['1f910', 'Zipper mouth face'], ['1f912', 'Face with thermometer'], ['1f913', 'Nerd face'], ['1f914', 'Thinking face'], ['1f917', 'Hugging face'], ['1f920', 'Cowboy hat face'], ['1f922', 'Nauseated face'], ['1f923', 'Rolling on the floor laughing'], ['1f925', 'Lying face'], ['1f927', 'Sneezing face'], ['1f928', 'Face with raised eyebrow'], ['1f92a', 'Crazy face'], ['1f92b', 'Shushing face'], ['1f92c', 'Face with symbols on mouth'], ['1f92f', 'Exploding head'], ['1f970', 'Smiling face with hearts'], ['1f973', 'Partying face'], ['1f974', 'Woozy face'], ['1f975', 'Hot face'], ['1f976', 'Cold face'], ['1f97a', 'Pleading face'],
].map(([hex, name]) => definition(hex, name, ['face', 'smiley']));

const gestures = [['1f44b', 'Waving hand'], ['1f44c', 'OK hand'], ['1f44e', 'Thumbs down'], ['1f64c', 'Raising hands']].map(([hex, name]) => definition(hex, name, ['gesture', 'hand']));

const feelings = [['1f493', 'Beating heart'], ['1f494', 'Broken heart'], ['1f496', 'Sparkling heart'], ['1f497', 'Growing heart'], ['1f498', 'Heart with arrow'], ['1f4a4', 'Zzz'], ['1f4a5', 'Collision'], ['1f4a6', 'Sweat droplets'], ['1f4a8', 'Dashing away'], ['1f4ab', 'Dizzy'], ['1f4a2', 'Anger symbol'], ['1f4a3', 'Bomb']].map(([hex, name]) => definition(hex, name, ['feeling']));

const activities = [['1f32d', 'Hot dog'], ['1f354', 'Hamburger'], ['1f355', 'Pizza'], ['1f35f', 'French fries'], ['1f369', 'Doughnut'], ['1f370', 'Shortcake'], ['1f377', 'Wine glass'], ['1f37a', 'Beer mug'], ['1f37b', 'Clinking beer mugs'], ['1f37f', 'Popcorn'], ['1f381', 'Wrapped gift'], ['1f382', 'Birthday cake'], ['1f388', 'Balloon'], ['1f3af', 'Direct hit'], ['1f3be', 'Tennis'], ['1f3c0', 'Basketball'], ['1f3c6', 'Trophy'], ['1f3c8', 'American football'], ['1f4a1', 'Light bulb'], ['1f4c5', 'Calendar'], ['1f4cc', 'Pushpin'], ['1f4e2', 'Loudspeaker'], ['1f514', 'Bell'], ['23f0', 'Alarm clock'], ['2615', 'Hot beverage'], ['26bd', 'Soccer ball'], ['1f942', 'Boxing glove'], ['1f947', 'Gold medal'], ['1f948', 'Silver medal'], ['1f949', 'Bronze medal']].map(([hex, name]) => definition(hex, name, ['activity', 'object']));

export const TEAMS_EMOJI_CATEGORIES: readonly TeamsEmojiCategory[] = [
  { id: 'popular', labelKey: 'cliq.emojiCategoryPopular', emoji: popular },
  { id: 'smileys', labelKey: 'cliq.emojiCategorySmileys', emoji: smileys },
  { id: 'gestures', labelKey: 'cliq.emojiCategoryGestures', emoji: gestures },
  { id: 'feelings', labelKey: 'cliq.emojiCategoryFeelings', emoji: feelings },
  { id: 'activities', labelKey: 'cliq.emojiCategoryActivities', emoji: activities },
];

export function getTeamsEmojiAsset(emoji: string): string {
  const codePoint = Array.from(emoji).find((character) => character !== '\uFE0F');
  return codePoint ? `/teams-emoji/${codePoint.codePointAt(0)?.toString(16)}_3d.png` : '';
}
