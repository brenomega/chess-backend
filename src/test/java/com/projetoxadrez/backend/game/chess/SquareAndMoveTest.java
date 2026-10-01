package com.projetoxadrez.backend.game.chess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SquareAndMoveTest {

    @Test
    void convertsSquaresAndMovesToChessNotation() {
        Square square = Square.fromAlgebraic("H8");
        Move move = Move.between("a2", "a4");

        assertThat(square).isEqualTo(new Square(7, 7));
        assertThat(square.toAlgebraic()).isEqualTo("h8");
        assertThat(move.toUci()).isEqualTo("a2a4");
    }

    @Test
    void rejectsCoordinatesOutsideTheBoard() {
        assertThatThrownBy(() -> new Square(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Square(0, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Square.fromAlgebraic("i1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Square.fromAlgebraic("a9")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMoveThatDoesNotChangeSquare() {
        assertThatThrownBy(() -> Move.between("e4", "e4")).isInstanceOf(IllegalArgumentException.class);
    }
}
