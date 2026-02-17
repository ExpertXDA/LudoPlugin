# 🎰 LUDOMANIA Configuration v4.1

---

# 🇷🇺 Русская версия

## 📌 Описание

Данный конфигурационный файл управляет механикой зависимости в системе **LUDOMANIA**.  
Настройки позволяют контролировать скорость фаз "Кайфа" и "Ломки", а также порог активации зависимости.

---

## ⚙️ Параметры конфигурации

### 1️⃣ high_speed_multiplier

**Скорость фазы "Кайфа" (от 100% до 20%)**

Определяет, насколько быстро проходит фаза положительного эффекта после активации зависимости.

- `1.0` — стандартная скорость
- `0.5` — фаза длится в 2 раза дольше
- `2.0` — фаза проходит в 2 раза быстрее

> Чем меньше значение, тем дольше игрок находится в состоянии "кайфа".

---

### 2️⃣ withdrawal_speed_multiplier

**Скорость фазы "Ломки" (от 20% до 0%)**

Определяет, сколько времени у игрока есть на внесение депозита во время негативной фазы.

- `0.1` — ломка длится очень долго (хардкорный режим)
- `1.0` — стандартная скорость
- `5.0` — ломка проходит очень быстро

> Малое значение увеличивает давление и усложняет геймплей.

---

### 3️⃣ min_ore_trigger

**Порог активации зависимости**

Количество руды, которое игрок должен сдать суммарно за 10 минут, чтобы запустить таймер зависимости.

Пример:

```yaml
min_ore_trigger: 10
```

Если игрок сдаёт **10 или более единиц руды** за 10 минут — активируется система зависимости.

---

## 🎮 Балансировка

- Уменьшайте `high_speed_multiplier` для более "затягивающего" эффекта.
- Уменьшайте `withdrawal_speed_multiplier` для повышения сложности.
- Повышайте `min_ore_trigger`, если хотите снизить частоту срабатывания механики.

---

---

# 🇬🇧 English Version

## 📌 Description

This configuration file controls the addiction mechanics of the **LUDOMANIA** system.  
It allows you to adjust the duration of the "High" and "Withdrawal" phases, as well as the activation threshold.

---

## ⚙️ Configuration Parameters

### 1️⃣ high_speed_multiplier

**Speed of the "High" phase (from 100% to 20%)**

Determines how quickly the positive effect phase decreases after activation.

- `1.0` — default speed
- `0.5` — lasts twice as long
- `2.0` — ends twice as fast

> Lower values make the "high" phase last longer.

---

### 2️⃣ withdrawal_speed_multiplier

**Speed of the "Withdrawal" phase (from 20% to 0%)**

Defines how much time the player has to deposit resources while suffering negative effects.

- `0.1` — extremely long withdrawal (hardcore mode)
- `1.0` — default speed
- `5.0` — very fast withdrawal

> Lower values increase pressure and difficulty.

---

### 3️⃣ min_ore_trigger

**Addiction Activation Threshold**

The total amount of ore deposited within 10 minutes required to activate the addiction timer.

Example:

```yaml
min_ore_trigger: 10
```

If a player deposits **10 or more ores** within 10 minutes, the addiction system activates.

---

## 🎮 Balancing Tips

- Decrease `high_speed_multiplier` to make the effect more addictive.
- Decrease `withdrawal_speed_multiplier` to increase difficulty.
- Increase `min_ore_trigger` to reduce activation frequency.