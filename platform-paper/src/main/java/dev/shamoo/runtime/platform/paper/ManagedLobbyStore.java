package dev.shamoo.runtime.platform.paper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Stable managed-lobby configuration storage with confined, durable atomic writes. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class ManagedLobbyStore {
    public static final int MAX_FILE_BYTES = 1_048_576;
    public static final int MAX_DIRECTORY_CHARS = 512;
    public static final List<String> FILES = List.of(
            "config.yml", "messages.yml", "items.yml", "menus.yml",
            "scoreboard.yml", "servers.yml", "spawn.yml", "portals.yml");
    // CHECKSTYLE:OFF
    private static final Map<String, String> DEFAULTS = Map.of(
            "config.yml", """
                    join:
                      suppress-message: true
                      teleport: true
                      reset: true
                      welcome-title: bienvenida
                      welcome-sound: bienvenida
                      welcome-particle: bienvenida
                      welcome-message: bienvenida
                    void-rescue-y: -80
                    protection:
                      enabled: true
                      bypass-permission: lobby.protection.bypass
                    portal-cooldown-ms: 2500
                    enforcement-ticks: 200
                    worlds:
                      - name: world
                        time: 6000
                        storm: false
                        thundering: false
                        game-rules:
                          announceAdvancements: false
                          commandBlockOutput: false
                          disableRaids: true
                          doDaylightCycle: false
                          doEntityDrops: false
                          doFireTick: false
                          doImmediateRespawn: true
                          doInsomnia: false
                          doMobLoot: false
                          doMobSpawning: false
                          doPatrolSpawning: false
                          doTileDrops: false
                          doTraderSpawning: false
                          doVinesSpread: false
                          doWardenSpawning: false
                          doWeatherCycle: false
                          drowningDamage: false
                          fallDamage: false
                          fireDamage: false
                          freezeDamage: false
                          keepInventory: true
                          mobGriefing: false
                          projectilesCanBreakBlocks: false
                          randomTickSpeed: 0
                          showDeathMessages: false
                          spawnRadius: 0
                          spectatorsGenerateChunks: false
                          tntExplodes: false
                    visibility:
                      default: all
                      staff-permission: lobby.visibility.staff
                    transfers:
                      cooldown-ms: 3000
                    """,
            "messages.yml", """
                    messages:
                      prefix: '<#303746>[</#303746><gradient:#38D9FF:#4F7CFF:#A855F7>ShaLobby</gradient><#303746>]</#303746> '
                      bienvenida: '<#303746>◆</#303746> <gradient:#38D9FF:#4F7CFF:#A855F7><bold>SHALOBBY</bold></gradient> <#303746>▸</#303746> <#A8B3C7>Bienvenido, <#F8FAFC>%player%</#F8FAFC>. Elige tu próxima aventura.</#A8B3C7>'
                      sin-permiso: '<#303746>◆</#303746> <#FF5C7A>No tienes permiso para realizar esta acción.</#FF5C7A>'
                      configuracion-invalida: '<#303746>◆</#303746> <#FF5C7A>La configuración no es válida. Consulta el registro del servidor.</#FF5C7A>'
                      recarga-completada: '<#303746>◆</#303746> <#55FF88>Configuración recargada correctamente.</#55FF88>'
                      recarga-fallida: '<#303746>◆</#303746> <#FF5C7A>No se pudo recargar la configuración.</#FF5C7A>'
                      spawn-no-configurado: '<#303746>◆</#303746> <#FFB347>El punto de aparición todavía no está configurado.</#FFB347>'
                      spawn-establecido: '<#303746>◆</#303746> <#55FF88>Punto de aparición guardado en <#F8FAFC>%world%</#F8FAFC>.</#55FF88>'
                      spawn-solicitado: '<#303746>◆</#303746> <#A8B3C7>Regresando al punto de aparición...</#A8B3C7>'
                      objetos-restaurados: '<#303746>◆</#303746> <#55FF88>Tu barra rápida ha sido restaurada.</#55FF88>'
                      menu-abierto: '<#303746>◆</#303746> <#A8B3C7>Menú <#F8FAFC>%menu%</#F8FAFC> abierto.</#A8B3C7>'
                      menu-no-disponible: '<#303746>◆</#303746> <#FF5C7A>El menú <#F8FAFC>%menu%</#F8FAFC> no está disponible.</#FF5C7A>'
                      visibilidad-todos: '<#303746>◆</#303746> <#55FF88>Ahora puedes ver a todos los jugadores.</#55FF88>'
                      visibilidad-personal: '<#303746>◆</#303746> <#FFD166>Ahora solo puedes ver al personal.</#FFD166>'
                      visibilidad-ninguno: '<#303746>◆</#303746> <#A8B3C7>Ahora solo puedes verte a ti mismo.</#A8B3C7>'
                      visibilidad-actualizada: '<#303746>◆</#303746> <gradient:#38D9FF:#4F7CFF:#A855F7>Visibilidad actualizada:</gradient> <#F8FAFC>%visibility%</#F8FAFC><#A8B3C7>.</#A8B3C7>'
                      transferencia-iniciada: '<#303746>◆</#303746> <#A8B3C7>Solicitando conexión con <#F8FAFC>%server%</#F8FAFC>...</#A8B3C7>'
                      transferencia-espera: '<#303746>◆</#303746> <#FFB347>Espera <#F8FAFC>%seconds%</#F8FAFC> s antes de cambiar de servidor.</#FFB347>'
                      item-cooldown: '<#303746>◆</#303746> <#FFB347>Espera <#F8FAFC>%seconds%</#F8FAFC> s antes de volver a usar este objeto.</#FFB347>'
                      portal-cooldown: '<#303746>◆</#303746> <#FFB347>Espera <#F8FAFC>%seconds%</#F8FAFC> s antes de volver a usar este portal.</#FFB347>'
                      servidor-no-disponible: '<#303746>◆</#303746> <#FF5C7A>El destino <#F8FAFC>%server%</#F8FAFC> no está disponible.</#FF5C7A>'
                      portal-varita: '<#303746>◆</#303746> <gradient:#38D9FF:#4F7CFF:#A855F7>Varita de portales entregada.</gradient> <#A8B3C7>Izquierdo: posición 1; derecho: posición 2.</#A8B3C7>'
                      portal-seleccion-incompleta: '<#303746>◆</#303746> <#FFB347>Selecciona las dos posiciones antes de crear el portal.</#FFB347>'
                      portal-mundos-distintos: '<#303746>◆</#303746> <#FF5C7A>Las dos posiciones deben pertenecer al mismo mundo.</#FF5C7A>'
                      portal-creado: '<#303746>◆</#303746> <#55FF88>Portal <#F8FAFC>%portal%</#F8FAFC> creado correctamente.</#55FF88>'
                      portal-eliminado: '<#303746>◆</#303746> <#FFB347>Portal <#F8FAFC>%portal%</#F8FAFC> eliminado.</#FFB347>'
                      portal-no-encontrado: '<#303746>◆</#303746> <#FF5C7A>No existe el portal <#F8FAFC>%portal%</#F8FAFC>.</#FF5C7A>'
                      portal-habilitado: '<#303746>◆</#303746> <#55FF88>Portal <#F8FAFC>%portal%</#F8FAFC> habilitado.</#55FF88>'
                      portal-deshabilitado: '<#303746>◆</#303746> <#FFB347>Portal <#F8FAFC>%portal%</#F8FAFC> deshabilitado.</#FFB347>'
                      portal-destino: '<#303746>◆</#303746> <#55FF88>El portal <#F8FAFC>%portal%</#F8FAFC> ahora conecta con <#F8FAFC>%server%</#F8FAFC>.</#55FF88>'
                      portal-visualizacion: '<#303746>◆</#303746> <#A8B3C7>Visualización de portales: <#F8FAFC>%enabled%</#F8FAFC>.</#A8B3C7>'
                      portal-lista: '<#303746>◆</#303746> <#A8B3C7>Portales configurados: <#F8FAFC>%count%</#F8FAFC>.</#A8B3C7>'
                      command-error: '%prefix%<#FF5C7A>No se pudo completar la operación. Revisa el registro del servidor.</#FF5C7A>'
                      player-required: '%prefix%<#FF5C7A>Este comando necesita un jugador válido.</#FF5C7A>'
                      invalid-arguments: '%prefix%<#FFB347>Los argumentos indicados no son válidos.</#FFB347>'
                      spawn-requested: '%prefix%<#55FF88>Teletransporte al lobby solicitado.</#55FF88>'
                      spawn-player-requested: '%prefix%<#55FF88>Teletransporte al lobby solicitado para <#F8FAFC>%player%</#F8FAFC>.</#55FF88>'
                      spawn-set: '%prefix%<#55FF88>Punto de aparición actualizado.</#55FF88>'
                      reload-complete: '%prefix%<#55FF88>Configuración recargada correctamente.</#55FF88>'
                      items-given: '%prefix%<#55FF88>Barra rápida administrada restaurada para <#F8FAFC>%player%</#F8FAFC>.</#55FF88>'
                      items-reset: '%prefix%<#55FF88>Barra rápida administrada restaurada para <#F8FAFC>%player%</#F8FAFC>.</#55FF88>'
                      menu-opened: '%prefix%<#55FF88>Menú <#F8FAFC>%menu%</#F8FAFC> abierto para <#F8FAFC>%player%</#F8FAFC>.</#55FF88>'
                      portal-wand: '%prefix%<#55FF88>Varita de portales entregada.</#55FF88> <#A8B3C7>La edición también requiere <#F8FAFC>lobby.protection.bypass</#F8FAFC>.</#A8B3C7>'
                      portal-created: '%prefix%<#55FF88>Portal <#F8FAFC>%portal%</#F8FAFC> creado.</#55FF88>'
                      portal-deleted: '%prefix%<#FFB347>Portal <#F8FAFC>%portal%</#F8FAFC> eliminado.</#FFB347>'
                      portal-list: '%prefix%<#A8B3C7>Portales configurados (<#F8FAFC>%count%</#F8FAFC>): <#F8FAFC>%ids%</#F8FAFC>.</#A8B3C7>'
                      portal-info: '%prefix%<#A8B3C7>Portal <#F8FAFC>%portal%</#F8FAFC>: mundo=<#F8FAFC>%world%</#F8FAFC>, min=<#F8FAFC>%minimum%</#F8FAFC>, max=<#F8FAFC>%maximum%</#F8FAFC>, permiso=<#F8FAFC>%permission%</#F8FAFC>, prioridad=<#F8FAFC>%priority%</#F8FAFC>, cooldown=<#F8FAFC>%cooldown% ms</#F8FAFC>, visualización=<#F8FAFC>%visualization%</#F8FAFC>, activo=<#F8FAFC>%enabled%</#F8FAFC>, destino=<#F8FAFC>%destination%</#F8FAFC>.</#A8B3C7>'
                      portal-enabled: '%prefix%<#55FF88>Portal <#F8FAFC>%portal%</#F8FAFC> activado.</#55FF88>'
                      portal-disabled: '%prefix%<#FFB347>Portal <#F8FAFC>%portal%</#F8FAFC> desactivado.</#FFB347>'
                      portal-pos1: '%prefix%<#55FF88>Primera posición guardada en <#F8FAFC>%world%</#F8FAFC> (<#F8FAFC>%x%, %y%, %z%</#F8FAFC>).</#55FF88>'
                      portal-pos2: '%prefix%<#55FF88>Segunda posición guardada en <#F8FAFC>%world%</#F8FAFC> (<#F8FAFC>%x%, %y%, %z%</#F8FAFC>).</#55FF88>'
                      portal-destination: '%prefix%<#55FF88>Destino de <#F8FAFC>%portal%</#F8FAFC> actualizado a <#F8FAFC>%destination%</#F8FAFC>.</#55FF88>'
                      portal-visualization-enabled: '%prefix%<#55FF88>Visualización de portales activada.</#55FF88>'
                      portal-visualization-disabled: '%prefix%<#FFB347>Visualización de portales desactivada.</#FFB347>'
                      status: '%prefix%<#A8B3C7>Estado: <#F8FAFC>%state%</#F8FAFC>, activo=<#F8FAFC>%active%</#F8FAFC>, admisión=<#F8FAFC>%admission%</#F8FAFC>, pendientes=<#F8FAFC>%pending%/%maximum%</#F8FAFC>, aparición=<#F8FAFC>%spawn%</#F8FAFC>, objetos=<#F8FAFC>%items%</#F8FAFC>, menús=<#F8FAFC>%menus%</#F8FAFC>, servidores=<#F8FAFC>%servers%</#F8FAFC>, portales=<#F8FAFC>%portals%</#F8FAFC>.</#A8B3C7>'
                      debug: '%prefix%<#A8B3C7>Diagnóstico: estado=<#F8FAFC>%state%</#F8FAFC>, activo=<#F8FAFC>%active%</#F8FAFC>, admisión=<#F8FAFC>%admission%</#F8FAFC>, pendientes=<#F8FAFC>%pending%/%maximum%</#F8FAFC>, generación=<#F8FAFC>%generation%</#F8FAFC>, directorio=<#F8FAFC>%directory%</#F8FAFC>, aparición=<#F8FAFC>%spawn%</#F8FAFC>, objetos=<#F8FAFC>%items%</#F8FAFC>, menús=<#F8FAFC>%menus%</#F8FAFC>, servidores=<#F8FAFC>%servers%</#F8FAFC>, portales=<#F8FAFC>%portals%</#F8FAFC>.</#A8B3C7>'
                      unavailable: '%prefix%<#FFB347>La operación no está disponible en el contexto actual. Revisa la configuración, el mundo administrado, la selección y los permisos requeridos.</#FFB347>'
                      unknown: '%prefix%<#FF5C7A>No se encontró el recurso solicitado.</#FF5C7A>'
                      invalid: '%prefix%<#FF5C7A>El Runtime rechazó la operación porque sus datos o configuración no son válidos.</#FF5C7A>'
                      overloaded: '%prefix%<#FFB347>El Runtime del lobby está ocupado. Inténtalo de nuevo en unos segundos.</#FFB347>'
                    titles:
                      - id: bienvenida
                        title: '<gradient:#38D9FF:#4F7CFF:#A855F7><bold>✦ SHALOBBY ✦</bold></gradient>'
                        subtitle: '<#A8B3C7>Tu aventura comienza aquí</#A8B3C7>'
                        fade-in-ticks: 10
                        stay-ticks: 60
                        fade-out-ticks: 20
                      - id: perfil
                        title: '<gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ TU PERFIL ◆</bold></gradient>'
                        subtitle: '<#A8B3C7>Jugador: <#F8FAFC>%player%</#F8FAFC> <#303746>•</#303746> Ping: <#F8FAFC>%ping% ms</#F8FAFC></#A8B3C7>'
                        fade-in-ticks: 5
                        stay-ticks: 45
                        fade-out-ticks: 10
                    sounds:
                      - id: bienvenida
                        sound: UI_TOAST_CHALLENGE_COMPLETE
                        volume: 0.8
                        pitch: 1.1
                      - id: clic
                        sound: UI_BUTTON_CLICK
                        volume: 0.7
                        pitch: 1.2
                      - id: confirmacion
                        sound: ENTITY_EXPERIENCE_ORB_PICKUP
                        volume: 0.8
                        pitch: 1.35
                    particles:
                      - id: bienvenida
                        particle: HAPPY_VILLAGER
                        count: 18
                        offset-x: 0.6
                        offset-y: 1.0
                        offset-z: 0.6
                        speed: 0.05
                      - id: destello
                        particle: END_ROD
                        count: 14
                        offset-x: 0.5
                        offset-y: 0.8
                        offset-z: 0.5
                        speed: 0.03
                    """,
            "items.yml", """
                    items:
                      - id: selector-juegos
                        slot: 0
                        material: COMPASS
                        amount: 1
                        name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Selector de juegos</bold></gradient>'
                        lore:
                          - '<italic:false><#A8B3C7>Explora las modalidades de la red.</#A8B3C7>'
                          - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para abrir</#F8FAFC>'
                        cooldown-ms: 500
                        action: { type: menu, target: game-selector }
                      - id: selector-lobbies
                        slot: 1
                        material: NETHER_STAR
                        amount: 1
                        name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>✦ Selector de lobbies</bold></gradient>'
                        lore:
                          - '<italic:false><#A8B3C7>Elige otro punto de encuentro.</#A8B3C7>'
                          - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para abrir</#F8FAFC>'
                        cooldown-ms: 500
                        action: { type: menu, target: lobby-selector }
                      - id: perfil
                        slot: 4
                        material: PLAYER_HEAD
                        amount: 1
                        name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Tu perfil</bold></gradient>'
                        lore:
                          - '<italic:false><#A8B3C7>Consulta tu resumen y preferencias.</#A8B3C7>'
                          - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para abrir</#F8FAFC>'
                        cooldown-ms: 500
                        action: { type: menu, target: profile }
                      - id: visibilidad
                        slot: 7
                        material: LIME_DYE
                        amount: 1
                        name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Visibilidad</bold></gradient>'
                        lore:
                          - '<italic:false><#A8B3C7>Alterna entre todos, personal y nadie.</#A8B3C7>'
                          - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para cambiar</#F8FAFC>'
                        cooldown-ms: 500
                        action: { type: visibility, target: cycle }
                      - id: ajustes
                        slot: 8
                        material: COMPARATOR
                        amount: 1
                        name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Ajustes</bold></gradient>'
                        lore:
                          - '<italic:false><#A8B3C7>Personaliza tu experiencia en el lobby.</#A8B3C7>'
                          - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para abrir</#F8FAFC>'
                        cooldown-ms: 500
                        action: { type: menu, target: settings }
                    """,
            "menus.yml", """
                    menus:
                      - id: game-selector
                        rows: 3
                        title: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ JUEGOS ◆</bold></gradient>'
                        slots:
                          - slot: 4
                            material: NETHER_STAR
                            amount: 1
                            name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>✦ Elige una aventura</bold></gradient>'
                            lore:
                              - '<italic:false><#A8B3C7>La disponibilidad se confirma al conectar.</#A8B3C7>'
                              - '<italic:false><#303746>•</#303746> <#F8FAFC>No se muestran estados estimados.</#F8FAFC>'
                            action: { type: none }
                          - slot: 10
                            material: GRASS_BLOCK
                            amount: 1
                            name: '<italic:false><#55FF88><bold>◆ Supervivencia</bold></#55FF88>'
                            lore:
                              - '<italic:false><#A8B3C7>Construye, comercia y progresa.</#A8B3C7>'
                              - '<italic:false><#A8B3C7>Estado actual: <#F8FAFC>por confirmar</#F8FAFC></#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#55FF88>Clic para solicitar conexión</#55FF88>'
                            action: { type: connect, target: survival }
                          - slot: 13
                            material: OAK_SAPLING
                            amount: 1
                            name: '<italic:false><#38D9FF><bold>◆ Skyblock</bold></#38D9FF>'
                            lore:
                              - '<italic:false><#A8B3C7>Haz crecer una isla desde cero.</#A8B3C7>'
                              - '<italic:false><#A8B3C7>Estado actual: <#F8FAFC>por confirmar</#F8FAFC></#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#38D9FF>Clic para solicitar conexión</#38D9FF>'
                            action: { type: connect, target: skyblock }
                          - slot: 16
                            material: SLIME_BALL
                            amount: 1
                            name: '<italic:false><#FFD166><bold>◆ Minijuegos</bold></#FFD166>'
                            lore:
                              - '<italic:false><#A8B3C7>Rondas rápidas para jugar en grupo.</#A8B3C7>'
                              - '<italic:false><#A8B3C7>Estado actual: <#F8FAFC>por confirmar</#F8FAFC></#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#FFD166>Clic para solicitar conexión</#FFD166>'
                            action: { type: connect, target: minigames }
                          - slot: 22
                            material: CLOCK
                            amount: 1
                            name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Cambiar de lobby</bold></gradient>'
                            lore:
                              - '<italic:false><#A8B3C7>Abre el selector de puntos de encuentro.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para continuar</#F8FAFC>'
                            action: { type: menu, target: lobby-selector }
                      - id: lobby-selector
                        rows: 3
                        title: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ LOBBIES ◆</bold></gradient>'
                        slots:
                          - slot: 4
                            material: CLOCK
                            amount: 1
                            name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>✦ Puntos de encuentro</bold></gradient>'
                            lore:
                              - '<italic:false><#A8B3C7>La red resolverá cada solicitud.</#A8B3C7>'
                              - '<italic:false><#303746>•</#303746> <#F8FAFC>Capacidad actual: desconocida</#F8FAFC>'
                            action: { type: none }
                          - slot: 11
                            material: SEA_LANTERN
                            amount: 1
                            name: '<italic:false><#38D9FF><bold>◆ Lobby 1</bold></#38D9FF>'
                            lore:
                              - '<italic:false><#A8B3C7>Disponibilidad: <#F8FAFC>por confirmar</#F8FAFC></#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#38D9FF>Clic para solicitar conexión</#38D9FF>'
                            action: { type: connect, target: lobby-1 }
                          - slot: 13
                            material: PRISMARINE_CRYSTALS
                            amount: 1
                            name: '<italic:false><#38D9FF><bold>◆ Lobby 2</bold></#38D9FF>'
                            lore:
                              - '<italic:false><#A8B3C7>Disponibilidad: <#F8FAFC>por confirmar</#F8FAFC></#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#38D9FF>Clic para solicitar conexión</#38D9FF>'
                            action: { type: connect, target: lobby-2 }
                          - slot: 15
                            material: HEART_OF_THE_SEA
                            amount: 1
                            name: '<italic:false><#38D9FF><bold>◆ Lobby 3</bold></#38D9FF>'
                            lore:
                              - '<italic:false><#A8B3C7>Disponibilidad: <#F8FAFC>por confirmar</#F8FAFC></#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#38D9FF>Clic para solicitar conexión</#38D9FF>'
                            action: { type: connect, target: lobby-3 }
                          - slot: 22
                            material: COMPASS
                            amount: 1
                            name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Volver a juegos</bold></gradient>'
                            lore:
                              - '<italic:false><#A8B3C7>Regresa al selector de modalidades.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para continuar</#F8FAFC>'
                            action: { type: menu, target: game-selector }
                      - id: profile
                        rows: 3
                        title: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ TU PERFIL ◆</bold></gradient>'
                        slots:
                          - slot: 4
                            material: PLAYER_HEAD
                            amount: 1
                            name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>✦ Resumen personal</bold></gradient>'
                            lore:
                              - '<italic:false><#A8B3C7>Tu información se muestra en el marcador.</#A8B3C7>'
                              - '<italic:false><#303746>•</#303746> <#F8FAFC>Datos actualizados por el lobby</#F8FAFC>'
                            action: { type: title, target: perfil }
                          - slot: 10
                            material: NAME_TAG
                            amount: 1
                            name: '<italic:false><#38D9FF><bold>◆ Identidad</bold></#38D9FF>'
                            lore:
                              - '<italic:false><#A8B3C7>Consulta tu nombre en el marcador lateral.</#A8B3C7>'
                            action: { type: none }
                          - slot: 12
                            material: GRASS_BLOCK
                            amount: 1
                            name: '<italic:false><#38D9FF><bold>◆ Ubicación</bold></#38D9FF>'
                            lore:
                              - '<italic:false><#A8B3C7>Mundo y coordenadas aparecen en el marcador.</#A8B3C7>'
                            action: { type: none }
                          - slot: 14
                            material: CLOCK
                            amount: 1
                            name: '<italic:false><#38D9FF><bold>◆ Conexión</bold></#38D9FF>'
                            lore:
                              - '<italic:false><#A8B3C7>Tu latencia actual aparece en el marcador.</#A8B3C7>'
                            action: { type: none }
                          - slot: 16
                            material: SPYGLASS
                            amount: 1
                            name: '<italic:false><#38D9FF><bold>◆ Visibilidad</bold></#38D9FF>'
                            lore:
                              - '<italic:false><#A8B3C7>Revisa tu modo activo en el marcador.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para alternarlo</#F8FAFC>'
                            action: { type: visibility, target: cycle }
                          - slot: 22
                            material: ENDER_PEARL
                            amount: 1
                            name: '<italic:false><#55FF88><bold>◆ Volver al inicio</bold></#55FF88>'
                            lore:
                              - '<italic:false><#A8B3C7>Regresa al punto de aparición configurado.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#55FF88>Clic para volver</#55FF88>'
                            action: { type: spawn }
                      - id: settings
                        rows: 3
                        title: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ AJUSTES ◆</bold></gradient>'
                        slots:
                          - slot: 4
                            material: COMPARATOR
                            amount: 1
                            name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>✦ Preferencias del lobby</bold></gradient>'
                            lore:
                              - '<italic:false><#A8B3C7>Elige cómo quieres ver a los demás.</#A8B3C7>'
                            action: { type: none }
                          - slot: 10
                            material: LIME_DYE
                            amount: 1
                            name: '<italic:false><#55FF88><bold>◆ Ver a todos</bold></#55FF88>'
                            lore:
                              - '<italic:false><#A8B3C7>Muestra a todos los jugadores del lobby.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#55FF88>Clic para aplicar</#55FF88>'
                            action: { type: visibility, target: all }
                          - slot: 12
                            material: YELLOW_DYE
                            amount: 1
                            name: '<italic:false><#FFD166><bold>◆ Ver al personal</bold></#FFD166>'
                            lore:
                              - '<italic:false><#A8B3C7>Muestra únicamente al equipo autorizado.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#FFD166>Clic para aplicar</#FFD166>'
                            action: { type: visibility, target: staff }
                          - slot: 14
                            material: GRAY_DYE
                            amount: 1
                            name: '<italic:false><#A8B3C7><bold>◆ Ocultar jugadores</bold></#A8B3C7>'
                            lore:
                              - '<italic:false><#A8B3C7>Oculta a los demás jugadores del lobby.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para aplicar</#F8FAFC>'
                            action: { type: visibility, target: none }
                          - slot: 16
                            material: AMETHYST_SHARD
                            amount: 1
                            name: '<italic:false><#38D9FF><bold>◆ Probar sonido</bold></#38D9FF>'
                            lore:
                              - '<italic:false><#A8B3C7>Reproduce una confirmación breve.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#38D9FF>Clic para escuchar</#38D9FF>'
                            action: { type: sound, target: clic }
                          - slot: 20
                            material: FIREWORK_STAR
                            amount: 1
                            name: '<italic:false><#38D9FF><bold>◆ Probar partículas</bold></#38D9FF>'
                            lore:
                              - '<italic:false><#A8B3C7>Muestra un destello a tu alrededor.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#38D9FF>Clic para mostrar</#38D9FF>'
                            action: { type: particle, target: destello }
                          - slot: 22
                            material: EXPERIENCE_BOTTLE
                            amount: 1
                            name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Ver resumen</bold></gradient>'
                            lore:
                              - '<italic:false><#A8B3C7>Muestra una tarjeta breve en pantalla.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para mostrar</#F8FAFC>'
                            action: { type: title, target: perfil }
                          - slot: 24
                            material: ENDER_PEARL
                            amount: 1
                            name: '<italic:false><#55FF88><bold>◆ Volver al inicio</bold></#55FF88>'
                            lore:
                              - '<italic:false><#A8B3C7>Regresa al punto de aparición configurado.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#55FF88>Clic para volver</#55FF88>'
                            action: { type: spawn }
                          - slot: 26
                            material: COMPASS
                            amount: 1
                            name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Selector de juegos</bold></gradient>'
                            lore:
                              - '<italic:false><#A8B3C7>Abre el catálogo de modalidades.</#A8B3C7>'
                              - '<italic:false><#303746>▸</#303746> <#F8FAFC>Clic para continuar</#F8FAFC>'
                            action: { type: menu, target: game-selector }
                    """,
            "scoreboard.yml", """
                    sidebar:
                      enabled: true
                      interval-ticks: 20
                      title-frames:
                        - '<gradient:#38D9FF:#4F7CFF:#A855F7><bold>✦ SHALOBBY ✦</bold></gradient>'
                        - '<gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ SHALOBBY ◆</bold></gradient>'
                        - '<gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ SHALOBBY ◆</bold></gradient>'
                        - '<gradient:#38D9FF:#4F7CFF:#A855F7><bold>✦ SHALOBBY ✦</bold></gradient>'
                      lines:
                        - '<#303746>◆ ───────────── ◆</#303746>'
                        - '<#A8B3C7>Jugador</#A8B3C7>'
                        - '<#F8FAFC>▸ <#38D9FF>%player%</#38D9FF></#F8FAFC>'
                        - ''
                        - '<#A8B3C7>Sesión</#A8B3C7>'
                        - '<#F8FAFC>▸ En línea: <#4F7CFF>%online%</#4F7CFF></#F8FAFC>'
                        - '<#F8FAFC>▸ Mundo: <#4F7CFF>%world%</#4F7CFF></#F8FAFC>'
                        - '<#F8FAFC>▸ Posición: <#4F7CFF>%x%, %y%, %z%</#4F7CFF></#F8FAFC>'
                        - '<#F8FAFC>▸ Ping: <#4F7CFF>%ping% ms</#4F7CFF></#F8FAFC>'
                        - ''
                        - '<#A8B3C7>Preferencias</#A8B3C7>'
                        - '<#F8FAFC>▸ Visibilidad: <#38D9FF>%visibility%</#38D9FF></#F8FAFC>'
                        - ''
                        - '<gradient:#38D9FF:#4F7CFF:#A855F7>✦ Elige tu próxima aventura ✦</gradient>'
                        - '<#303746>◆ ───────────── ◆</#303746>'
                    """,
            "servers.yml", """
                    servers:
                      - id: survival
                        enabled: true
                        target: survival
                        display-name: '<italic:false><#55FF88><bold>◆ Supervivencia</bold></#55FF88>'
                      - id: skyblock
                        enabled: true
                        target: skyblock
                        display-name: '<italic:false><#38D9FF><bold>◆ Skyblock</bold></#38D9FF>'
                      - id: minigames
                        enabled: true
                        target: minigames
                        display-name: '<italic:false><#FFD166><bold>◆ Minijuegos</bold></#FFD166>'
                      - id: lobby-1
                        enabled: true
                        target: lobby-1
                        display-name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Lobby 1</bold></gradient>'
                      - id: lobby-2
                        enabled: true
                        target: lobby-2
                        display-name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Lobby 2</bold></gradient>'
                      - id: lobby-3
                        enabled: true
                        target: lobby-3
                        display-name: '<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7><bold>◆ Lobby 3</bold></gradient>'
                    """,
            "spawn.yml", """
                    spawn: { configured: false }
                    """,
            "portals.yml", """
                    portals:
                      - id: portal-survival
                        enabled: false
                        world: world
                        min: { x: -12, y: 64, z: 18 }
                        max: { x: -8, y: 68, z: 20 }
                        permission: lobby.portal.survival
                        priority: 10
                        cooldown-ms: 2500
                        destination: survival
                        action: { type: connect, target: survival }
                        visualize: false
                      - id: portal-skyblock
                        enabled: false
                        world: world
                        min: { x: -2, y: 64, z: 18 }
                        max: { x: 2, y: 68, z: 20 }
                        permission: lobby.portal.skyblock
                        priority: 10
                        cooldown-ms: 2500
                        destination: skyblock
                        action: { type: connect, target: skyblock }
                        visualize: false
                      - id: portal-minigames
                        enabled: false
                        world: world
                        min: { x: 8, y: 64, z: 18 }
                        max: { x: 12, y: 68, z: 20 }
                        permission: lobby.portal.minigames
                        priority: 10
                        cooldown-ms: 2500
                        destination: minigames
                        action: { type: connect, target: minigames }
                        visualize: false
                    """);
    // CHECKSTYLE:ON

    private final Path directory;
    private final DirectorySync directorySync;
    private long version;

    public ManagedLobbyStore(Path directory) {
        this.directory = boundedDirectory(directory);
        directorySync = this::forceDirectoryNative;
    }

    ManagedLobbyStore(Path directory, DirectorySync directorySync) {
        this.directory = boundedDirectory(directory);
        this.directorySync = Objects.requireNonNull(directorySync, "directorySync");
    }

    public Path directory() {
        return directory;
    }

    public static Path resolveExistingAncestors(Path path) throws IOException {
        Path absolute = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("managed lobby path has no existing filesystem ancestor");
        }
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    }

    public synchronized <T> T transaction(Transaction<T> transaction) throws Exception {
        return Objects.requireNonNull(transaction, "transaction").execute(this);
    }

    public synchronized void ensure() throws IOException {
        verifyNoSymbolicLinkAncestors();
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("managed lobby data directory must not be a symbolic link");
        }
        Files.createDirectories(directory);
        verifyNoSymbolicLinkAncestors();
        for (String file : FILES) {
            Path path = resolve(file);
            if (Files.isSymbolicLink(path)) {
                throw new IOException("managed lobby file must not be a symbolic link: " + file);
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                writeBytes(path, DEFAULTS.get(file).getBytes(StandardCharsets.UTF_8));
            } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("managed lobby path is not a regular file: " + file);
            }
        }
    }

    public synchronized Map<String, String> readAll() throws IOException {
        ensure();
        Map<String, String> result = new LinkedHashMap<>();
        for (String file : FILES) {
            result.put(file, read(file));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public synchronized Snapshot snapshot() throws IOException {
        return new Snapshot(version, readAll());
    }

    public synchronized Snapshot writeIfUnchanged(Snapshot expected, String file, String content) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(content, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("managed lobby file content exceeds 1 MiB");
        }
        ensure();
        Path target = resolve(file);
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        Path stagedTarget = stageBytes(target, bytes);
        Path stagedBackup = null;
        try {
            stagedBackup = stageBytes(backup,
                    Objects.requireNonNull(expected.files().get(file), "expected managed lobby file")
                            .getBytes(StandardCharsets.UTF_8));
            requireUnchanged(expected);
            moveAtomic(stagedBackup, backup);
            moveAtomic(stagedTarget, target);
            version++;
            forceDirectory();
            Map<String, String> committed = new LinkedHashMap<>(expected.files());
            committed.put(file, content);
            return new Snapshot(version, committed);
        } finally {
            Files.deleteIfExists(stagedTarget);
            if (stagedBackup != null) {
                Files.deleteIfExists(stagedBackup);
            }
        }
    }

    public synchronized void requireUnchanged(Snapshot expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        if (version != expected.version() || !readAll().equals(expected.files())) {
            throw new StaleSnapshotException();
        }
    }

    public synchronized void runAtVersion(long expectedVersion, Runnable action) {
        if (version != expectedVersion) {
            throw new StaleSnapshotException();
        }
        Objects.requireNonNull(action, "action").run();
    }

    public synchronized void runAtSnapshot(Snapshot expected, Runnable action) {
        try {
            requireUnchanged(expected);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        Objects.requireNonNull(action, "action").run();
    }

    public synchronized String read(String file) throws IOException {
        Path path = resolve(file);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("managed lobby file is not a regular confined file: " + file);
        }
        long size = Files.size(path);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("managed lobby file exceeds 1 MiB: " + file);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public synchronized void write(String file, String content) throws IOException {
        Objects.requireNonNull(content, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("managed lobby file content exceeds 1 MiB");
        }
        ensure();
        Path path = resolve(file);
        if (Files.isSymbolicLink(path)) {
            throw new IOException("managed lobby file must not be a symbolic link: " + file);
        }
        if (Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("existing managed lobby file exceeds 1 MiB: " + file);
        }
        byte[] previous = Files.readAllBytes(path);
        writeBytes(path.resolveSibling(path.getFileName() + ".bak"), previous);
        writeVersionedBytes(path, bytes);
    }

    private Path resolve(String file) {
        if (!FILES.contains(file)) {
            throw new IllegalArgumentException("unknown managed lobby file: " + file);
        }
        Path resolved = directory.resolve(file).normalize();
        Path parent = resolved.getParent();
        if (parent == null || !parent.equals(directory)) {
            throw new IllegalArgumentException("managed lobby file escapes the data directory");
        }
        return resolved;
    }

    private static Path boundedDirectory(Path path) {
        Path result = Objects.requireNonNull(path, "directory").toAbsolutePath().normalize();
        if (result.toString().length() > MAX_DIRECTORY_CHARS) {
            throw new IllegalArgumentException("managed lobby directory exceeds 512 characters");
        }
        return result;
    }

    private void verifyNoSymbolicLinkAncestors() throws IOException {
        Path current = directory.getRoot();
        for (Path element : directory) {
            current = Objects.requireNonNull(current, "managed lobby directory root").resolve(element);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("managed lobby data directory must not have symbolic-link ancestors: " + current);
            }
        }
    }

    private void writeBytes(Path target, byte[] bytes) throws IOException {
        Path temporary = stageBytes(target, bytes);
        try {
            moveAtomic(temporary, target);
            forceDirectory();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeVersionedBytes(Path target, byte[] bytes) throws IOException {
        Path temporary = stageBytes(target, bytes);
        try {
            moveAtomic(temporary, target);
            version++;
            forceDirectory();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path stageBytes(Path target, byte[] bytes) throws IOException {
        String targetName = Objects.requireNonNull(target.getFileName(), "target file name").toString();
        Path temporary = target.resolveSibling('.' + targetName + '.' + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        return temporary;
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("managed lobby storage requires same-filesystem atomic rename", exception);
        }
    }

    private void forceDirectory() throws IOException {
        directorySync.sync();
    }

    private void forceDirectoryNative() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("managed lobby storage requires directory fsync support", exception);
        }
    }

    @FunctionalInterface
    public interface Transaction<T> {
        T execute(ManagedLobbyStore store) throws Exception;
    }

    @FunctionalInterface
    interface DirectorySync {
        void sync() throws IOException;
    }

    public record Snapshot(long version, Map<String, String> files) {
        public Snapshot {
            files = Map.copyOf(files);
        }
    }

    public static final class StaleSnapshotException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private StaleSnapshotException() {
            super("managed lobby configuration changed concurrently; retry the operation");
        }
    }
}
