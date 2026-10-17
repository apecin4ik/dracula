DraculaTower — установка
1) Нужен WorldEdit (или FAWE) и Paper 1.20.1.
2) mvn package -> target/DraculaTower-1.0.0.jar в plugins/
3) Схематика (basnea_drakula.schem) уже внутри jar и сама появится в plugins/DraculaTower/.
   Хочешь свою — положи файл туда с тем же именем, внутри должен стоять BLACK_SHULKER_BOX (место приза).
4) В plugins/DraculaTower/loot.yml замени "inv: {}" на секцию inv из своего конфига.
Команды: /dracula (для всех), /dracula start|stop|reload|tp (право dracula.admin).
Файлы состояния: state.yml, terrain-backup.schem (удаляются сами по окончании ивента).
