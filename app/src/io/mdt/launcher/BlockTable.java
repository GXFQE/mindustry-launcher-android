package io.mdt.launcher;

/**
 * 自动生成的「方块名 → 配色 / 占地格数」表（勿手改）。
 * 生成器：MDT-Android-Dev/_lab/msch/GenBlockTable.java
 *        （工作副本：MDT-Android/.tmp-prev-lab/GenBlockTable.java，两份一样）
 * 生成于 2026-10-06；447 行（有配色 424，没有 23）。
 * 列（Tab 分隔）：name argb size rotate rotateDraw
 *   · argb = 8 位十六进制 ARGB（alpha 一律 FF —— 游戏 loadColors() 之后就是纯色）；
 *     `-` = 这个方块**没有配色**（空气 / 建造占位 build1..16 / 遗留方块）。
 *   · size = 占地格数（游戏 `Block.size`）—— 多格方块要靠它才知道占几格。
 *   · rotate / rotateDraw = 游戏 `Block.drawDefaultPlanRegion` 里那两句判据
 *     `(!rotate || !rotateDraw) ? 0 : rotation * 90` —— **像素级预览**要靠它决定转不转
 *     （墙 / 地板这类是 0/0，转了就是错的）。
 * 编码：UTF-8 文本 → Deflater 压缩 → Base64（标准字母表，无换行）；
 *   产品侧用 {@link MapStats#inflate} 解（同一套编码，不另写解码器）。
 *
 * <h3>两个来源</h3>
 * 名字 + 尺寸 = 桌面 <code>Mindustry-160.4.jar</code> 里 `content.blocks()` 的**顺序**；
 * 颜色       = **同一版本** Android 包 <code>[Android][v160]Mindustry.apk</code> 的 `sprites/block_colors.png` 第 i 列
 *              （游戏 `ContentLoader.loadColors()` 就是拿 `pixmap.get(i,0)` 喂给第 i 个方块）。
 * 🔴 **对齐证据**（生成器当场算的）：矿类方块的颜色来自**物品**（jar 自己就带），
 *   而调色板在那些列上**恰好是空洞**（`loadColors` 对 `color == 0` 直接 continue）
 *   ⇒ 「空洞位置 == jar 自带颜色的位置」= 13/13 全部命中（v146 同样成立）。
 *
 * 🔴 **为什么烘颜色、不烘 content id**：`.msch` 里存的是**名字**，而调色板按 **id** 索引；
 *   但 id **会在版本之间漂**——实测 152.2 → 160.4 在**中间插进**方块（`ore-wall-tungsten`），
 *   之后 265 处全部错位；而我们把 v146~v160 + MindustryX 都当一等公民。
 *   颜色则几乎不随版本变：同名的 412 个方块里只有 5 个不同，每个通道只差 1~4。
 *
 * ⚠️ 模组方块（名字带模组前缀）**不在表里** ⇒ 调用方按「认不出」处理（画成中性色），
 *   这与蓝图页那句「（可能认错）」是同一条纪律：不确定就**别装成确定**。
 */
final class BlockTable {
    private BlockTable() {}

    /** 表本体（见类注释的列定义与编码） */
    static final String DATA_B64 =
            "eNqFWtmWozgSfa7+F58DCG2fow2baTAeMJnl/vq5WhFOV0/mk7laQqFYbgSocf11+dX+an61f20P9X3/NQxKEtrx9HB18/LlLt9q" +
            "mgANA2uIOUPL6oCYviedSoiZxmHAQx7+00O9j5Nty27hZ4ef3fGT4Cc5fvb42R8/KX7S4yfDT3b85PjJj58CP8XxU+KnrMRovBxN" +
            "9SAIVgvqRWsr2VovXFtJ13rx2kq+1gvYVhK2XsQ2ymide0CLT7dCL8T2UjRZ7TfodvkuYM9pL7LSnmq8P509QEUk7xIY1nwfgbuT" +
            "lOQRav17U3f7c5QkXZ+vK4zICO+Z4Op9/rE64bQtwvlHLWtFm83lsSwTtjHraxmmfbTAaS+tpgmfl+np7pdtUtdgNIyQ7jA/4y2p" +
            "oU3T5odufjxfh4U+l7sf0nf4z1ua1ct2KRjBf8FuQURiiOnzklptanr6p203dFnRt+W5LubvsAARJB9xVtdZJYAJaJ/WOhumZfHr" +
            "Ky4kG9505ndoOte5/Hxc/a693zfrd979sE5Cg7RMN+PTH4QLLgXLvnZ7LVN8TjWRZdH8/BK14GFJeFdcdNmvt0s9uSVdp4sDX/3z" +
            "m9+s6zXJm71cMMisUd73tmBGrXq5F6zrOlbWG9y6jqZgUGNXFJmwKOYW74SSbKfara9pquZ2ErrT5YZf2xNOMt7dMcB2vFxeGlDu" +
            "g6gO1/jhNJfHhP399lC4JaIowpaFhYZ3tsXD7pu71LCE4fV9NW8MRmta5VReDgbw8pdY5KGUN0RX6ElLvSw+UK7zy92DsXTQoDur" +
            "PkEtb2U7nNdMUCcI6+Sn46cB1BDRsffDZ5SZzhH+Qfd556E1XVe7ZEYIIW1RavSzIhPrEHGOLWcVzkc7XZxBT7uLj/3l9jk+Xle1" +
            "bSGOiL6Xxf2CC2ujrc5K2O7Lt39GNNdZ2fF2VKc60x2PLmmoNNKVoT4OhwCiesaOeBX3ZoTR7FQGGe/yT7w9pqnuywoPjxxTBBMn" +
            "LaUkyhX+h9OchAhNmaRVvChTmp4VhcQIkSHVqFaL6mzpuSW2HWrVZMD5vFyC1X7IRRWVVL7FhgwaJRVVn2wqy9GzoRfvdpzlpz0l" +
            "8mzHCer73tD2QwTJA5hlpOj+HCjKGo5a2px9Ic/moqfsT4GkjMLZmDx7deE82hpHfjhLQrWmhNYz7ajmJWTMqG0iWsbOiTZRKQvz" +
            "rcy5AP0gjou7rbveggVozXhtqkW38BXVnMzpMeZY5WRfm0ACnKtMID2jSg5Fzd83f3nP1bmLdcrnKNc6NfyEvaExuKA87W+mfYvZ" +
            "yPS6G9Shnm/n/GrWwlhyXHvs60Xv2y0EJipUf7IyeJsKimSiK/all32yka4YbkRbn/HATpYedVZN5FwpWV/MgXkBtTy724HCE5R2" +
            "5yh3oFTTw1GSqR8okgcC2dnaK5lgKnR4N/VqbXAt+jGvVdI1zPT07AsHyigSH31Lm8d1iZ5ZkQPl16hXdX9efg7TkjtN3lbRE2iS" +
            "N1UzqPbQQEKXNZqxkYZ1H9yxEpFTxtibL1Z34+CO7XucOXDJWN/rH+56DNCG0uKxs6t5AwDOmp/QxSoQwWC58Paeug9DOg829Dh5" +
            "DZIYXvWRVyqw96ClCEIfQBoSMhW9qejl5aHubrq0kUa5QncrzIvTt8DoT8xLQzQxn7A+UmjaDz8xLwvyECnqrTAWwjDmneQMJ/HX" +
            "7kRTapp4vOc4uS0eQRFVGFcN+jMgb5tCE2swqBR1VClHa9CfAqWFIp/29MfwzL9w2hr05xAMVIN+APm/SSsicfksrQz1Bviw/KSE" +
            "JrJl8VHcNujIlzX6E9qFUgFS2U9ouGpGODmbrE8dQfcIuKaklQrzq8ILWak1K4xENyAlUpllWrynZTfSrTKFkmQwZ7fG6cFW5Zky" +
            "vnpbvtw6qVegeXB/v2v7acAlpJ6QWPx/GrZ6FvNhieYNQyjbf4fJ8PR8hw9QwCnUBLh1XmwbZapdxylQuQ4OkS8u5Lfl8QixxEpp" +
            "OamQKWZLYfig6hkbCp9HzDr4Oy0VHATFDO/q58/xqe7jPvvFrGpdvcnztqwRGqQipp6WkkYAiRID6+t5+/2KCO57PJwJpLFajhSn" +
            "/2XtcPn/D/+jAKDxD9zdwcwQLBXT7/PTMH/FujPMdj8E+MMh8sTLY3WBgEsiuWpTe2nep+dYIOFkJ3VqNW0jkqwvaWc3pSwohQFv" +
            "6k4wTMeMOlQIQgkLmh6n/z1OoWlGpJImzUGVucXrw/3OYdfYJ3CaKZUH3RRqy2+nviJTYrqRNm+6r1dXSaR70FORdizNlcs8/o45" +
            "T6MGKOu+UGN7RWRUK2VRm6ZelhethlCLZB2V3bSUpQzZ3AM++IyO3aBiGtJwO24ozNwMnUQRoYCi00hD803gX2cBHvuEA4//RE13" +
            "gsq2RApPMlAuruOwX4MFtF7AMLH5a7wbEIUsirC8YTmo5TtSq7kM+3qPjSROmINzRonc5MxzXaZX3Jij7ObuFwnhQz1Rsz1uLjQo" +
            "lnsQIW/TCph5WmP5PVqo1hvDTc06UTbB0kJN2gSr3FzqxgjBB+FP4GHf9Tog2YO75pnRGCoMNijSNP/U9yDGFasv1apx6jZ7t/gw" +
            "CEWmYKc1lj1JRZGQRZ7vpTorVzWiF6TisKN1tQOohoPJ5bv280/XJpln5RkOplx7D7YXTbbll7ovV98RfN2fN7dlu6DCKJbGRN2c" +
            "B0iE2LJIOrs6tKNBYuPpYqguVSyTWhz2VqDLpNYguuFhQDTVHINLpQXZXc5dJ7DMh14H9yEM5GqwG1pZeqVnuKxhtdOyyTLEeFs6" +
            "BE60pZlaY2V2GnGKMbnidK3OnOdAyky/sxpOESjPtNby4mwFOWZa6005xYVIAuSAKqn0kT2VzaOlUb1meR+fGEsPgaLGzHZ3IGUm" +
            "AkxvxI+Zl1u0O8WRMnJcruDreEUdM5o4hBiW3io8UVynigbSClTjgVD8VXJYaQ8wxUtP/IwW2aiTCmwp3VpKUuXaOtGXOHcCD8ux" +
            "MC9zitJJk1yCGsqErG68D8tqfGFzuqIBHk1L2fNhUHXProN3dyfvLl0coYp91Vh1e6LX2Tq32+hQVtVNDV3AGQwqKheFjzqIL0rt" +
            "x7r8J8cpyQIa53iiBtr15U5DNBJ5SeXHELvMIUsoJBiXrj0c+zy5AUnIuXO7oUhF+ostD1lVZCuSZBCnRWYw9Uuqy3P5TtmKM96n" +
            "lVbnw1a9EUocjgxOQrYKG30jt5fZiOmtKNYZFHfWAxEiBHWPB21f/jyqTxHs/uVe4TlDJgpit3VsqgdwBtaTBpwIShnCex67YCEp" +
            "rnPg7fUSvjAiCf/Pfjc+F8YkSYp163W0PuBXyxpuhTrFnQrVBmGidJOXNfojMndTbmHE4NW/vSowODQpDlnSGhdcCVHapxsykt6j" +
            "2k7e5S1o8L2Tq4pvWhpw7xzedm+0J1Ryym1Bp0WlRovylWB5T7Rtl2CVMZpIcPZ0lXY3z8A4oN7hTbkJQ7Y10ibMP6tSNXQrXIKK" +
            "4HnNeKoIHoLnValft1413k0SJuTBAlUHQ2Z1MptBSt/HfaG04cyc0CIs2FoXiGOUZ3xeEEKuy+XQWSdsSM3NeUDc/fJYxvCyQHKh" +
            "xUFLwbbuowE5fOzzIwSpoN58+0+1vjIke2lMzi3jDLIJa0uYGKQ0QyYey93uY9hMYLUsdJxwgEJLbtrDa57RIzKqBFU8odP4X8/J" +
            "izIkk0rlXJ9ATPVvYiMuVWNyGE44fPLvONWZzDoTVPmbsro/GpDF35JQSKJHCC/ulg9LoJ8PaSKpCI7KRfcz1VRHpkqLrK1qxE8p" +
            "UbYeOa8a+UNgDqPg8o9LHu5NwFv1zwU/6BasnBdC8nNoUjN8oCsk8uHD9OW+2Nhd0Lr01A/koIlCDyUhRSfIYd62qJ54qZHieqoN" +
            "KSoeUasnDvSKy3BZ+r3x8bEH1V1KJ95cZw2u4msPpJ1C1DUKu9LhBz1eUQu8DQgbJ0GfTs1nmCt4UxEWzGAFkR/fFjGtJjI7zvq8" +
            "nkBfSxyqWCB97AiGUCG74qcVdHDVBokj0/zMZisij6iM+p5kZ1ampvkaTBphKn2A4U+WLo8z2ZRwHoB8N7hwJrJfBWQaoyUQxKVs" +
            "Cc8dtOceLdS//w3O2sA1ckQ3NzeHeFTdSlUR0uBDJBfjqDm3cTvpDNtxnhWKUv53fWiHuN2mD13ubvGpelTVANSNAw4RnLgOjr5b" +
            "NcXSyIlSbd/dPivfdSkwSiCVIyTWhmkXSEqhs+SJgiZIKYRJk5QdPga5uN/PtcjUhpmJUvpey1c6quwkKdst43SahgSKjJE29C+K" +
            "TzpnDkwsX3/4pMiXkdstsymkt1DdZrb0PiQUwE2q8b0aZ3XR8WslZb2aT7PPA6BEnmrIYnpFTQTrdkkXbt0fwQAyCptmcFt6vCre" +
            "bmq1obYE0eXFoYEMC5K2SvHSWE0Rc/oDvu9mcvsWgwo9L6rVlrMBlV3FCH0vD/TPBg+E3wrB6nnKrMtjmcYttKMkKF36iKoOn1og" +
            "LGS29KX2+K5d+75uYb91bjhymq/u+c+4m9cAEbdCF2K0BM9CEM68dDMhCMZauxUq3dAG0c0t3rqRTRp8U2M0aE9Y0jPPuMM+IAK2" +
            "3O/d5Iw8mCY9VasJpQnScGZevtE1Tep3qBOV0VkD2zf4WqpkwDFZlkpNX0s28bzZ5q5z/OJBGZSrpZWw7Xc1j94+QOClSZY17FuI" +
            "WEbEfmTIgOPjERslOhUMoRP+MlP6AqWF7aq8AO4U9mXD5wxCIrx1qYjdHr4XFdaxvvxMj32TD6NDguaeP+aa1weYW3hDCDJM0vI+" +
            "JSQRQfp4doht19M4J3rMmC1eFgqPONrxvCWY+MOtcZWe8yKKGoZpjDxWorBi6bF/yxjkhhz0OA4s4+biN4VNWXqbY6sYmQJbhpjo" +
            "ufg0XsMBGwRakZ5eV+9slyHEnlfUo5RZcDWuFYTbIDLr4K6+/Nu4AwS3CNkwzLN2fPpadHXwAwi+5zAOxxO5wRi6zw8cVn0eiz9I" +
            "EuV0vx+455iEPy46/OJR1853KT+up2AH6pdMhOrhD5epNdIY/guJChgS3uoCuQNllTlYe5KEY4OumZy2GJMsnwm16eMMM8UEt/nI" +
            "SExvsGZHOA6Lr+68fMewQb382wDYSM9FvcH7AMFi4zF48zrO7scePbM4Aj2EqHvYouWeYcbUGiSoUdio78McifeMcq5RbUUU0Rkp" +
            "t8CXebH7FD/qw/4qjQoFUL6DTFE6XHKOGg/1CnVRXYNbmH/2/owfFJlxWUJGTe9/LtTbfqD8z0PLmuG9NXnb8VzvcsXbEjNSOv04" +
            "EH4quUrqj41r+2a8SJJDabK8g6Jlhh158DSPQrPDSYa3AT3mDmnvLF6pTKmgjPG3U1ZFMeuoodm4Y0WwLftqUrXQvVULX0v49hUZ" +
            "Vovc+UKomo9JnqPytobSHNkiLHfnurHM8h+XyLeiMs1DxYL6NkuRTlAmIkd2/P30aaYIRDxpNTTTDylBLUJLxdvr02sVPGifZx8I" +
            "FSoLZK1Ua0/TPo/3/ALB6k7nKDO5qzKvS/CYBxLl5fw8eEEOr/+CXVT1rfonPEb4MgTkfPaf9fhXE24tH5pPCiVqFCSok5XKRtkv" +
            "zxRgFPUQpcFSSaJXoBJ2vF8PCPMT5L+vXkEi7y70I5QxbjrqLN+DlOn79Nltm4r1Tx/qn1QgfY/PkIE5ikJZvkIZQdh8B9CUl4iN" +
            "ZKWEn5YrAs0brExuwd5e/g3Hj9nZvWY3e8VB0sBce27l8RVBgHSslD0RL2Vk3NNn9fi6Hb7fCnZqXL4PQWAWCKwsvTmZfo6AKkQx" +
            "bKPuX2qLnyd0lJabC05dMNLL/gMfrdRLQKyyer+XNbZSiyqMhHuaE5w0EV6/dSekWhUld2lnRrBcHYrhobxf+h/WYRgC";

    /** 表里有多少个方块（自检拿它证伪「表是空的」） */
    static final int COUNT = 447;
}
