OUTPUT_ARCH( "riscv" )
ENTRY(_start)

MEMORY
{
  ram (rwx) : ORIGIN = 0x00000000, LENGTH = 4K
}

SECTIONS
{
  /DISCARD/ : { *(.note.gnu.build-id) }

  .text :
  {
    *(.text.init)
    *(.text)
  } > ram

  .data :
  {
    *(.data)
  } > ram

  .bss :
  {
    *(.bss)
  } > ram

  _end = .;
}
